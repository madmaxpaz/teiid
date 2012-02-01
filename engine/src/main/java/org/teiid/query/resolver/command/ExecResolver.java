/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.resolver.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.teiid.api.exception.query.QueryMetadataException;
import org.teiid.api.exception.query.QueryResolverException;
import org.teiid.client.metadata.ParameterInfo;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.types.DataTypeManager;
import org.teiid.query.QueryPlugin;
import org.teiid.query.mapping.relational.QueryNode;
import org.teiid.query.metadata.QueryMetadataInterface;
import org.teiid.query.metadata.StoredProcedureInfo;
import org.teiid.query.metadata.TempMetadataAdapter;
import org.teiid.query.metadata.TempMetadataID;
import org.teiid.query.metadata.TempMetadataStore;
import org.teiid.query.resolver.ProcedureContainerResolver;
import org.teiid.query.resolver.QueryResolver;
import org.teiid.query.resolver.util.ResolverUtil;
import org.teiid.query.resolver.util.ResolverVisitor;
import org.teiid.query.sql.lang.Command;
import org.teiid.query.sql.lang.GroupContext;
import org.teiid.query.sql.lang.ProcedureContainer;
import org.teiid.query.sql.lang.SPParameter;
import org.teiid.query.sql.lang.StoredProcedure;
import org.teiid.query.sql.lang.SubqueryContainer;
import org.teiid.query.sql.symbol.Expression;
import org.teiid.query.sql.symbol.GroupSymbol;
import org.teiid.query.sql.visitor.ValueIteratorProviderCollectorVisitor;


/**
 */
public class ExecResolver extends ProcedureContainerResolver {
	
    /**
     * @see org.teiid.query.resolver.CommandResolver#findCommandMetadata(org.teiid.query.sql.lang.Command,
     * org.teiid.query.metadata.QueryMetadataInterface)
     */
    private void findCommandMetadata(Command command, TempMetadataStore discoveredMetadata, QueryMetadataInterface metadata)
    throws QueryMetadataException, QueryResolverException, TeiidComponentException {

        StoredProcedure storedProcedureCommand = (StoredProcedure) command;
        
        StoredProcedureInfo storedProcedureInfo = null;
        try {
        	storedProcedureInfo = metadata.getStoredProcedureInfoForProcedure(storedProcedureCommand.getProcedureName());
        } catch (QueryMetadataException e) {
        	String[] parts = storedProcedureCommand.getProcedureName().split("\\.", 2); //$NON-NLS-1$
	    	if (parts.length > 1 && parts[0].equalsIgnoreCase(metadata.getVirtualDatabaseName())) {
	            try {
	            	storedProcedureInfo = metadata.getStoredProcedureInfoForProcedure(parts[1]);
	            	storedProcedureCommand.setProcedureName(parts[1]);
	            } catch(QueryMetadataException e1) {
	            } 
	        }
	    	if (storedProcedureInfo == null) {
	    		throw e;
	    	}
        }

        storedProcedureCommand.setUpdateCount(storedProcedureInfo.getUpdateCount());
        storedProcedureCommand.setModelID(storedProcedureInfo.getModelID());
        storedProcedureCommand.setProcedureID(storedProcedureInfo.getProcedureID());
        storedProcedureCommand.setProcedureCallableName(storedProcedureInfo.getProcedureCallableName());

        // Get old parameters as they may have expressions set on them - collect
        // those expressions to copy later into the resolved parameters
        List<SPParameter> oldParams = storedProcedureCommand.getParameters();

        boolean namedParameters = storedProcedureCommand.displayNamedParameters();
        
        // If parameter count is zero, then for the purposes of this method treat that
        // as if named parameters were used.  Even though the StoredProcedure was not
        // parsed that way, the user may have entered no parameters with the intention
        // of relying on all default values of all optional parameters.
        if (oldParams.size() == 0 || (oldParams.size() == 1 && storedProcedureCommand.isCalledWithReturn())) {
        	storedProcedureCommand.setDisplayNamedParameters(true);
            namedParameters = true;
        }
        
        // Cache original input parameter expressions.  Depending on whether
        // the procedure was parsed with named or unnamed parameters, the keys
        // for this map will either be the String names of the parameters or
        // the Integer indices, as entered in the user query
        Map<Integer, Expression> postionalExpressions = new HashMap<Integer, Expression>();
        Map<String, Expression> namedExpressions = new TreeMap<String, Expression>(String.CASE_INSENSITIVE_ORDER);
        int adjustIndex = 0;
        for (SPParameter param : oldParams) {
            if(param.getExpression() == null) {
            	if (param.getParameterType() == SPParameter.RESULT_SET) {
            		adjustIndex--;  //If this was already resolved, just pretend the result set param doesn't exist
        		}
            	continue;
            }
            if (namedParameters && param.getParameterType() != SPParameter.RETURN_VALUE) {
                if (namedExpressions.put(param.getName(), param.getExpression()) != null) {
                	 throw new QueryResolverException(QueryPlugin.Event.TEIID30138, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30138, param.getName().toUpperCase()));
                }
            } else {
                postionalExpressions.put(param.getIndex() + adjustIndex, param.getExpression());
            }
        }

        storedProcedureCommand.clearParameters();
        int origInputs = postionalExpressions.size() + namedExpressions.size();
        /*
         * Take the values set from the stored procedure implementation, and match up with the
         * types of parameter it is from the metadata and then reset the newly joined parameters
         * into the stored procedure command.  If it is a result set get those columns and place
         * them into the stored procedure command as well.
         */
        List<SPParameter> metadataParams = storedProcedureInfo.getParameters();
        List<SPParameter> clonedMetadataParams = new ArrayList<SPParameter>(metadataParams.size());
        int inputParams = 0;
        int outParams = 0;
        boolean hasReturnValue = false;
        for (SPParameter metadataParameter : metadataParams) {
            if( (metadataParameter.getParameterType()==ParameterInfo.IN) ||
                (metadataParameter.getParameterType()==ParameterInfo.INOUT)){

                inputParams++;
            } else if (metadataParameter.getParameterType() == ParameterInfo.OUT) {
            	outParams++;
            } else if (metadataParameter.getParameterType() == ParameterInfo.RETURN_VALUE) {
            	hasReturnValue = true;
            }
            SPParameter clonedParam = (SPParameter)metadataParameter.clone();
            clonedMetadataParams.add(clonedParam);
            storedProcedureCommand.setParameter(clonedParam);
        }
        
        if (storedProcedureCommand.isCalledWithReturn() && !hasReturnValue) {
        	 throw new QueryResolverException(QueryPlugin.Event.TEIID30139, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30139, storedProcedureCommand.getGroup()));
        }

        if(!namedParameters && (inputParams > postionalExpressions.size())) {
             throw new QueryResolverException(QueryPlugin.Event.TEIID30140, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30140, inputParams, origInputs, storedProcedureCommand.getGroup()));
        }
        
        // Walk through the resolved parameters and set the expressions from the
        // input parameters
        int exprIndex = 1;
        HashSet<String> expected = new HashSet<String>();
        if (storedProcedureCommand.isCalledWithReturn() && hasReturnValue) {
	        for (SPParameter param : clonedMetadataParams) {
	        	if (param.getParameterType() == SPParameter.RETURN_VALUE) {
	        		Expression expr = postionalExpressions.remove(exprIndex++);
	                param.setExpression(expr);
	        	}
	        }
        }
        for (SPParameter param : clonedMetadataParams) {
            if(param.getParameterType() == SPParameter.RESULT_SET || param.getParameterType() == SPParameter.RETURN_VALUE) {
            	continue;
            }
            if (namedParameters) {
                String nameKey = param.getParameterSymbol().getShortName();
                Expression expr = namedExpressions.remove(nameKey);
                // With named parameters, have to check on optional params and default values
                if (expr == null && param.getParameterType() != ParameterInfo.OUT) {
                	expr = ResolverUtil.getDefault(param.getParameterSymbol(), metadata);
                	param.setUsingDefault(true);
                	expected.add(nameKey);
                } 
                param.setExpression(expr);                    
            } else {
            	if(param.getParameterType() == SPParameter.OUT) {
            		continue;
            	}
                Expression expr = postionalExpressions.remove(exprIndex++);
                param.setExpression(expr);
            }
        }
        
        // Check for leftovers, i.e. params entered by user w/ wrong/unknown names
        if (!namedExpressions.isEmpty()) {
    		 throw new QueryResolverException(QueryPlugin.Event.TEIID30141, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30141, namedExpressions.keySet(), expected));
        }
        if (!postionalExpressions.isEmpty()) {
        	 throw new QueryResolverException(QueryPlugin.Event.TEIID30142, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30142, inputParams, origInputs, storedProcedureCommand.getGroup().toString()));
        }
        
        // Create temporary metadata that defines a group based on either the stored proc
        // name or the stored query name - this will be used later during planning
        String procName = storedProcedureCommand.getProcedureName();
        List tempElements = storedProcedureCommand.getProjectedSymbols();
        boolean isVirtual = storedProcedureInfo.getQueryPlan() != null;
        discoveredMetadata.addTempGroup(procName, tempElements, isVirtual);

        // Resolve tempElements against new metadata
        GroupSymbol procGroup = new GroupSymbol(storedProcedureInfo.getProcedureCallableName());
        procGroup.setProcedure(true);
        TempMetadataID tid = discoveredMetadata.getTempGroupID(procName);
        tid.setOriginalMetadataID(storedProcedureCommand.getProcedureID());
        procGroup.setMetadataID(tid);
        storedProcedureCommand.setGroup(procGroup);
    }
    
    /** 
     * @see org.teiid.query.resolver.ProcedureContainerResolver#resolveProceduralCommand(org.teiid.query.sql.lang.Command, org.teiid.query.metadata.TempMetadataAdapter)
     */
    public void resolveProceduralCommand(Command command, TempMetadataAdapter metadata) 
        throws QueryMetadataException, QueryResolverException, TeiidComponentException {

        findCommandMetadata(command, metadata.getMetadataStore(), metadata);
        
        //Resolve expressions on input parameters
        StoredProcedure storedProcedureCommand = (StoredProcedure) command;
        GroupContext externalGroups = storedProcedureCommand.getExternalGroupContexts();
        for (SPParameter param : storedProcedureCommand.getParameters()) {
            Expression expr = param.getExpression();
            if(expr == null) {
            	continue;
            }
            for (SubqueryContainer<?> container : ValueIteratorProviderCollectorVisitor.getValueIteratorProviders(expr)) {
                QueryResolver.setChildMetadata(container.getCommand(), command);
                
                QueryResolver.resolveCommand(container.getCommand(), metadata.getMetadata());
            }
            ResolverVisitor.resolveLanguageObject(expr, null, externalGroups, metadata);
            Class<?> paramType = param.getClassType();

            ResolverUtil.setDesiredType(expr, paramType, storedProcedureCommand);
            
            // Compare type of parameter expression against parameter type
            // and add implicit conversion if necessary
            Class<?> exprType = expr.getType();
            if(paramType == null || exprType == null) {
                 throw new QueryResolverException(QueryPlugin.Event.TEIID30143, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30143, storedProcedureCommand.getProcedureName(), param.getName()));
            }
            String tgtType = DataTypeManager.getDataTypeName(paramType);
            String srcType = DataTypeManager.getDataTypeName(exprType);
            Expression result = null;
                            
            if (param.getParameterType() == SPParameter.RETURN_VALUE || param.getParameterType() == SPParameter.OUT) {
            	if (!ResolverUtil.canImplicitlyConvert(tgtType, srcType)) {
            		 throw new QueryResolverException(QueryPlugin.Event.TEIID30144, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30144, param.getParameterSymbol(), tgtType, srcType));
            	}
            } else {
                try {
                    result = ResolverUtil.convertExpression(expr, tgtType, metadata);
                } catch (QueryResolverException e) {
                     throw new QueryResolverException(QueryPlugin.Event.TEIID30145, e, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30145, new Object[] { srcType, tgtType}));
                }                                                       
                param.setExpression(result);
            }
        }
    }
    
    protected void resolveGroup(TempMetadataAdapter metadata,
                                ProcedureContainer procCommand) throws TeiidComponentException,
                                                              QueryResolverException {
        //Do nothing
    }

    /** 
     * @throws QueryResolverException 
     * @see org.teiid.query.resolver.ProcedureContainerResolver#getPlan(org.teiid.query.metadata.QueryMetadataInterface, org.teiid.query.sql.symbol.GroupSymbol)
     */
    protected String getPlan(QueryMetadataInterface metadata,
                             GroupSymbol group) throws TeiidComponentException,
                                               QueryMetadataException, QueryResolverException {
        StoredProcedureInfo storedProcedureInfo = metadata.getStoredProcedureInfoForProcedure(group.getName());
        
        //if there is a query plan associated with the procedure, get it.
        QueryNode plan = storedProcedureInfo.getQueryPlan();
        
        if (plan.getQuery() == null) {
             throw new QueryResolverException(QueryPlugin.Event.TEIID30146, QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30146));
        }
        
        return plan.getQuery();
    }
}

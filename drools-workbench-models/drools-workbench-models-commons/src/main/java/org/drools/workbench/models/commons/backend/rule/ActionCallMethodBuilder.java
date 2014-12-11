package org.drools.workbench.models.commons.backend.rule;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang.NumberUtils;
import org.drools.core.util.DateUtils;
import org.drools.workbench.models.datamodel.oracle.DataType;
import org.drools.workbench.models.datamodel.oracle.MethodInfo;
import org.drools.workbench.models.datamodel.oracle.PackageDataModelOracle;
import org.drools.workbench.models.datamodel.rule.ActionCallMethod;
import org.drools.workbench.models.datamodel.rule.ActionFieldFunction;
import org.drools.workbench.models.datamodel.rule.FieldNatureType;
import org.drools.workbench.models.datamodel.rule.RuleModel;

import static org.drools.workbench.models.commons.backend.rule.RuleModelPersistenceHelper.*;

public class ActionCallMethodBuilder {

    private RuleModel model;
    private PackageDataModelOracle dmo;
    private boolean isJavaDialect;
    private Map<String, String> boundParams;
    private String methodName;
    private String variable;
    private String[] parameters;
    private int index;

    public ActionCallMethodBuilder( RuleModel model,
                                    PackageDataModelOracle dmo,
                                    boolean isJavaDialect,
                                    Map<String, String> boundParams ) {
        this.model = model;
        this.dmo = dmo;
        this.isJavaDialect = isJavaDialect;
        this.boundParams = boundParams;
    }

    public ActionCallMethod get( String variable,
                                 String methodName,
                                 String[] parameters ) {
        this.variable = variable;
        this.methodName = methodName;
        this.parameters = parameters;

        ActionCallMethod actionCallMethod = new ActionCallMethod();
        actionCallMethod.setMethodName( methodName );
        actionCallMethod.setVariable( variable );
        actionCallMethod.setState( ActionCallMethod.TYPE_DEFINED );

        for ( ActionFieldFunction parameter : getActionFieldFunctions() ) {
            actionCallMethod.addFieldValue( parameter );
        }

        return actionCallMethod;
    }

    private List<ActionFieldFunction> getActionFieldFunctions() {

        List<ActionFieldFunction> actionFieldFunctions = new ArrayList<ActionFieldFunction>();

        this.index = 0;
        for ( String param : parameters ) {
            param = param.trim();
            if ( param.length() == 0 ) {
                continue;
            }

            actionFieldFunctions.add( getActionFieldFunction( param,
                                                              getDataType( param ) ) );
        }
        return actionFieldFunctions;
    }

    private ActionFieldFunction getActionFieldFunction( String param,
                                                        String dataType ) {
        final int fieldNature = inferFieldNature( dataType,
                                                  param,
                                                  boundParams,
                                                  isJavaDialect );

        //If the field is a formula don't adjust the param value
        String paramValue = param;
        switch ( fieldNature ) {
            case FieldNatureType.TYPE_FORMULA:
                break;
            case FieldNatureType.TYPE_VARIABLE:
                break;
            default:
                paramValue = adjustParam( dataType,
                                          param,
                                          boundParams,
                                          isJavaDialect );
        }
        ActionFieldFunction actionField = new ActionFieldFunction( methodName,
                                                                   paramValue,
                                                                   dataType );
        actionField.setNature( fieldNature );
        return actionField;
    }

    private String getDataType( String param ) {
        String dataType;

        MethodInfo methodInfo = getMethodInfo();

        if ( methodInfo != null ) {
            dataType = methodInfo.getParams().get( index++ );
        } else {
            dataType = boundParams.get( param );
        }
        if ( dataType == null ) {
            dataType = inferDataType( param,
                                      boundParams,
                                      isJavaDialect );
        }
        return dataType;
    }

    private MethodInfo getMethodInfo() {
        String variableType = boundParams.get( variable );
        if ( variableType != null ) {
            List<MethodInfo> methods = getMethodInfosForType( model,
                                                              dmo,
                                                              variableType );
            if ( methods != null ) {

                ArrayList<MethodInfo> methodInfos = getMethodInfos( methodName, methods );

                if ( methodInfos.size() > 1 ) {
                    // Now if there were more than one method with the same name
                    // we need to start figuring out what is the correct one.
                    for ( MethodInfo methodInfo : methodInfos ) {
                        if ( compareParameters( methodInfo.getParams() ) ) {
                            return methodInfo;
                        }
                    }
                } else if ( !methodInfos.isEmpty() ) {
                    // Not perfect, but works on most cases.
                    // There is no check if the parameter types match.
                    return methodInfos.get( 0 );
                }
            }
        }

        return null;
    }

    private ArrayList<MethodInfo> getMethodInfos( String methodName,
                                                  List<MethodInfo> methods ) {
        ArrayList<MethodInfo> result = new ArrayList<MethodInfo>();
        for ( MethodInfo method : methods ) {
            if ( method.getName().equals( methodName ) ) {
                result.add( method );
            }
        }
        return result;
    }

    private boolean compareParameters( List<String> methodParams ) {
        if ( methodParams.size() != parameters.length ) {
            return false;
        } else {
            for ( int index = 0; index < methodParams.size(); index++ ) {
                final String methodParamDataType = methodParams.get( index );
                final String paramDataType = assertParamDataType( methodParamDataType,
                                                                  parameters[ index ].trim() );
                if ( !methodParamDataType.equals( paramDataType ) ) {
                    return false;
                }
            }
            return true;
        }
    }

    private String assertParamDataType( final String methodParamDataType,
                                        final String paramValue ) {
        if ( boundParams.containsKey( paramValue ) ) {
            final String boundParamDataType = boundParams.get( paramValue );
            return boundParamDataType;
        } else {
            if ( DataType.TYPE_BOOLEAN.equals( methodParamDataType ) ) {
                if ( Boolean.TRUE.equals( Boolean.parseBoolean( paramValue ) ) || Boolean.FALSE.equals( Boolean.parseBoolean( paramValue ) ) ) {
                    return methodParamDataType;
                }
                return null;

            } else if ( DataType.TYPE_DATE.equals( methodParamDataType ) ) {
                try {
                    new SimpleDateFormat( DateUtils.getDateFormatMask(), Locale.ENGLISH ).parse( adjustParam( methodParamDataType,
                                                                                                              paramValue,
                                                                                                              Collections.EMPTY_MAP,
                                                                                                              isJavaDialect ) );
                    return methodParamDataType;
                } catch ( ParseException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_STRING.equals( methodParamDataType ) ) {
                if ( paramValue.startsWith( "\"" ) ) {
                    return methodParamDataType;
                }

            } else if ( DataType.TYPE_NUMERIC.equals( methodParamDataType ) ) {
                if ( !NumberUtils.isNumber( paramValue ) ) {
                    return methodParamDataType;
                }

            } else if ( DataType.TYPE_NUMERIC_BIGDECIMAL.equals( methodParamDataType ) ) {
                try {
                    new BigDecimal( adjustParam( methodParamDataType,
                                                 paramValue,
                                                 Collections.EMPTY_MAP,
                                                 isJavaDialect ) );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_BIGINTEGER.equals( methodParamDataType ) ) {
                try {
                    new BigInteger( adjustParam( methodParamDataType,
                                                 paramValue,
                                                 Collections.EMPTY_MAP,
                                                 isJavaDialect ) );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_BYTE.equals( methodParamDataType ) ) {
                try {
                    new Byte( paramValue );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_DOUBLE.equals( methodParamDataType ) ) {
                try {
                    new Double( paramValue );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_FLOAT.equals( methodParamDataType ) ) {
                try {
                    new Float( paramValue );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_INTEGER.equals( methodParamDataType ) ) {
                try {
                    new Integer( paramValue );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_LONG.equals( methodParamDataType ) ) {
                try {
                    new Long( paramValue );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            } else if ( DataType.TYPE_NUMERIC_SHORT.equals( methodParamDataType ) ) {
                try {
                    new Short( paramValue );
                    return methodParamDataType;
                } catch ( NumberFormatException e ) {
                    return null;
                }

            }

            return null;
        }

    }

}

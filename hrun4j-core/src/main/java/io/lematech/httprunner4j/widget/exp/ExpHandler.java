package io.lematech.httprunner4j.widget.exp;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import io.lematech.httprunner4j.common.Constant;
import io.lematech.httprunner4j.common.DefinedException;
import io.lematech.httprunner4j.entity.http.RequestEntity;
import io.lematech.httprunner4j.entity.testcase.Config;
import io.lematech.httprunner4j.widget.utils.RegExpUtil;
import io.lematech.httprunner4j.widget.log.MyLog;
import lombok.Data;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;

/**
 * @author lematech@foxmail.com
 * @version 1.0.0
 * @className ExpressionProcessor
 * @description TODO
 * @created 2021/1/26 5:20 下午
 * @publicWechat lematech
 */
@Data
public class ExpHandler<T> {

    private Map<String, Object> currentVariable = new HashMap<>();
    private Map<String, Object> configVars = new HashMap<>();
    private Map<String, Object> testStepVars = new HashMap<>();

    /**
     * expression evaluator
     *
     * @param t
     * @return
     */
    public <T> T executeExpression(T t) {
        if (Objects.isNull(t)) {
            return null;
        }
        if (t instanceof Map) {
            Map instance = (Map) t;
            Map<String, Object> result = new HashMap<>();
            Iterator it = instance.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> entry = (Map.Entry) it.next();
                String key = entry.getKey();
                String value = String.valueOf(entry.getValue());
                if (RegExpUtil.isExp(value)) {

                    Class valueClass = entry.getValue().getClass();
                    String newValue = executeStringExpression(value);
                    MyLog.debug("before execute expression : key-value : {}-{},after execute expression key-value : {}-{}}", key, value, key, newValue);
                    if (valueClass == Integer.class) {
                        Integer valueObj = Integer.parseInt(newValue);
                        result.put(key, valueObj);
                    } else if (valueClass == String.class) {
                        result.put(key, newValue);
                    } else if (valueClass == Long.class) {
                        result.put(key, Long.valueOf(newValue));
                    } else if (valueClass == Double.class) {
                        result.put(key, Double.valueOf(newValue));
                    } else if (valueClass == Float.class) {
                        result.put(key, Float.valueOf(newValue));
                    }
                } else {
                    result.put(key, value);
                }
            }
            return (T)result;
        }else if (t instanceof String) {
            String str = (String) t;
            return (T) executeStringExpression(str);
        } else if (t instanceof RequestEntity || t instanceof Config) {
            return (T) buildNewObj(t);
        }
        return t;
    }


    /**
     * execute string expression
     * @param str
     * @return
     */
    public String executeStringExpression(String str){
        if(RegExpUtil.isExp(str)){
            List<String> matchList = RegExpUtil.find(Constant.REGEX_EXPRESSION,str);
            List<String> matcherList = new ArrayList<>();
            for(String exp : matchList) {
                Object result;
                try {
                    result = BuiltInAviatorEvaluator.execute(exp, currentVariable);
                } catch (Exception e) {
                    String exceptionMsg = String.format("exp: %s occur error", exp);
                    throw new DefinedException(exceptionMsg);
                }
                String handleResult = String.valueOf(result);
                matcherList.add(handleResult);
            }
            for(String match : matcherList){
                str = str.replaceFirst(Constant.REGEX_EXPRESSION_REPLACE,match);
            }
        }
        return str;
    }

    /**
     * handle variables(config variable、teststep variale) expression in test context variable
     *
     * @param configVars
     * @param testStepVars
     */
    private void handleVariablesExpression(Map<String, Object> configVars, Map<String, Object> testStepVars) {
        this.configVars = (Map<String, Object>) executeExpression((T) configVars);
        this.testStepVars = (Map<String, Object>) executeExpression((T) testStepVars);
    }

    /**
     * handle setup or teardwon hook expression
     */
    public Map handleHookExp(Object hookObj) {
        Map result = Maps.newHashMap();
        if (hookObj instanceof Map) {
            handleMapExp(result, (Map<String, String>) hookObj);
        } else if (hookObj instanceof List) {
            List tempList = (List) hookObj;
            for (Object obj : tempList) {
                if (obj instanceof String) {
                    this.executeStringExpression(String.valueOf(obj));
                } else if (obj instanceof Map) {
                    handleMapExp(result, (Map<String, String>) obj);
                }
            }
        }
        return result;
    }

    private void handleMapExp(Map result, Map<String, String> hookObj) {
        Map<String, String> tempMap = hookObj;
        for (Map.Entry<String, String> entry : tempMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            result.put(key, this.executeStringExpression(value));
        }
    }

    /**
     * handle variables priority
     *
     * @param testContextVariable
     * @param configVars
     * @param testStepVars
     */
    public void setVariablePriority(Map<String, Object> currentVariable, Map<String, Object> testContextVariable, Map<String, Object> configVars, Map<String, Object> testStepVars) {
        handleVariablesExpression(configVars, testStepVars);
        currentVariable.putAll(MapUtil.isEmpty(configVars) ? org.testng.collections.Maps.newHashMap() : configVars);
        currentVariable.putAll(testContextVariable);
        currentVariable.putAll(MapUtil.isEmpty(testStepVars) ? org.testng.collections.Maps.newHashMap() : testStepVars);
        this.currentVariable = currentVariable;

    }

    /**
     * execute requestentity all properties value include expression language
     * @param object
     * @return
     */
    private Object buildNewObj(Object object){
        Field[] fields = object.getClass().getDeclaredFields();
        int fieldLength = fields.length;
        for(int index = 0;index<fieldLength;index++){
            String attributeName  = fields[index].getName();
            Class attributeClass = fields[index].getType();
            fields[index].setAccessible(true);
            String methodName=attributeName.substring(0,1).toUpperCase()+attributeName.substring(1);
            try{
                Object fieldValue = getFieldValueByName(fields[index].getName(), object);
                if(fieldValue == null){
                   continue;
                }
                if(attributeClass == String.class && StrUtil.isEmpty(String.valueOf(fieldValue))){
                    continue;
                }
                if(attributeClass == BigDecimal.class){
                    Method setMethod=object.getClass().getMethod("set"+methodName,BigDecimal.class);
                    setMethod.invoke(object,new BigDecimal(0));
                }else if(attributeClass==Long.class){
                    Method setMethod=object.getClass().getMethod("set"+methodName,Long.class);
                    setMethod.invoke(object,0L);
                }else if(attributeClass==String.class){
                    Method setMethod=object.getClass().getMethod("set"+methodName,String.class);
                    setMethod.invoke(object,executeExpression((T) fieldValue));
                } else if (attributeClass == Map.class) {
                    Method setMethod = object.getClass().getMethod("set" + methodName, Map.class);
                    setMethod.invoke(object, executeExpression((T) fieldValue));
                } else if (attributeClass == JSONObject.class) {
                    Method setMethod = object.getClass().getMethod("set" + methodName, JSONObject.class);
                    setMethod.invoke(object, JSON.parseObject((String) executeExpression((T) JSON.toJSONString(fieldValue)), JSONObject.class));
                }
            }catch (NoSuchMethodException e) {
                String exceptionMsg = String.format("no such method exception %s",e.getMessage());
                throw new DefinedException(exceptionMsg);
            } catch (IllegalAccessException e) {
                String exceptionMsg = String.format("illegal access exception %s",e.getMessage());
                throw new DefinedException(exceptionMsg);
            } catch (InvocationTargetException e) {
                String exceptionMsg = String.format("invocation target exception %s",e.getMessage());
                throw new DefinedException(exceptionMsg);
            }
        }
        return object;
    }

    /**
     * get object field value by name
     * @param fieldName
     * @param o
     * @return
     */
    private Object getFieldValueByName(String fieldName, Object o) {
        try {
            String firstLetter = fieldName.substring(0, 1).toUpperCase();
            String getter = "get" + firstLetter + fieldName.substring(1);
            Method method = o.getClass().getMethod(getter, new Class[] {});
            Object value = method.invoke(o, new Object[] {});
            return value;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }



}
package io.lematech.httprunner4j;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.StrUtil;
import io.lematech.httprunner4j.common.Constant;
import io.lematech.httprunner4j.common.DefinedException;
import io.lematech.httprunner4j.entity.testcase.TestCase;
import io.lematech.httprunner4j.entity.testcase.TestCaseMeta;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.velocity.VelocityContext;
import org.testng.ITestListener;
import org.testng.TestNG;
import org.testng.reporters.JUnitXMLReporter;
import org.uncommons.reportng.HTMLReporter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Slf4j
public class TestNGEngine {
    private static TestNG testNG;
    private static String suiteName;
    private static SchemaValidator schemaValidator = new SchemaValidator();
    private static Map<String, List<TestCaseMeta>> testCasePkgGroup = new HashMap<>();
    public static TestNG getInstance(){
        if(testNG == null){
            testNG = new TestNG();
            setDefaultProperties();
        }
        return testNG;
    }
    private static void setDefaultProperties(){
        testNG.setDefaultSuiteName("httprunner4j");
        HTMLReporter htmlReporter = new HTMLReporter();
        JUnitXMLReporter jUnitXMLReporter = new JUnitXMLReporter();
        testNG.addListener(htmlReporter);
        testNG.addListener(jUnitXMLReporter);
    }

    /**
     * self-defined add listener
     * @param testListenerList
     * @return
     */
    public static TestNG addListener(List<ITestListener> testListenerList){
        getInstance();
        if(testListenerList.size()>0){
            for(ITestListener testListener : testListenerList){
                testNG.addListener(testListener);
            }
        }
        return testNG;
    }
    public static void run(String path){
        if(StrUtil.isEmpty(path)){
            path = ".";
        }
        File currentPath = new File(path);
        String execPath = currentPath.getAbsolutePath();
        log.debug("execute path : [{}] test cases",execPath);
        traversePkgTestCaseGroup(currentPath);
        if(preValidationExceptionMap.size()>0){
            throw new DefinedException(preValidationExceptionMap.toString());
        }
        if(testCasePkgGroup == null || testCasePkgGroup.size() ==0){
            log.warn("in path [{}] not found valid testcases",execPath);
        }
        addTestClasses();
        run();
    }
    private static void run(){
        getInstance().run();
    }

    private static void addTestClasses(){
        List<Class> classes = new ArrayList<>();
        for(Map.Entry<String,List<TestCaseMeta>> entry:testCasePkgGroup.entrySet()){
            String fullTestClassName = entry.getKey();
            List methodNameList = entry.getValue();
            String pkgName = StrUtil.subBefore(fullTestClassName,".",true);
            String className = StrUtil.upperFirst(StrUtil.subAfter(fullTestClassName,".",true));
            VelocityContext ctx = new VelocityContext();
            ctx.put("pkgName", pkgName);
            ctx.put("className", className);
            ctx.put("methodList", methodNameList);
            String templateRenderContent = TemplateEngine.getTemplateRenderContent(Constant.TEST_TEMPLATE_FILE_PATH,ctx);
            log.info("test case content:{}",templateRenderContent);
            Class<?> clazz = HotLoader.hotLoadClass(pkgName,className,templateRenderContent);
            classes.add(clazz);

            log.debug("class full path：[{}],pkg path：[{}],class name：{} added done.",fullTestClassName,pkgName,className);
        }
        Class [] execClass = classes.toArray(new Class[0]);
        getInstance().setTestClasses(execClass);
    }

    private static List<String> preValidationExceptionMap =  new ArrayList<>();
    private static void traversePkgTestCaseGroup(File listFile){
        if(!listFile.exists()){
            String exceptionMsg = String.format("file: %s is not exist",listFile.getName());
            throw new DefinedException(exceptionMsg);
        }
        File [] files = listFile.listFiles();
        for(File file : files){
            String extName = FileUtil.extName(file);
            if(file.isFile()){
                String fileMainName = FileNameUtil.mainName(file.getName());
                String pkgPath = file.getParent().replace(Constant.TEST_CASE_FILE_PATH,"");
                if(Constant.SUPPORT_TEST_CASE_FILE_EXT_JSON_NAME.equalsIgnoreCase(extName)
                        ||Constant.SUPPORT_TEST_CASE_FILE_EXT_YML_NAME.equalsIgnoreCase(extName)){
                    if(!JavaIdentifierUtil.isValidJavaIdentifier(fileMainName)){
                        String exceptionMsg = String.format("%s.%s,file name is invalid,not apply java identifier,please modify it",pkgPath,fileMainName);
                        preValidationExceptionMap.add(exceptionMsg);
                        continue;
                    }
                    ITestCaseLoader testCaseLoader = TestCaseLoaderFactory.getLoader(extName);
                    TestCase testCase = testCaseLoader.load(file);
                    try{
                        SchemaValidator.validateTestCaseValid(testCase);
                    }catch (DefinedException e){
                        String exceptionMsg = String.format("%s.%s,file schema validate failure,exception:%s",pkgPath,fileMainName,e.getMessage());
                        preValidationExceptionMap.add(exceptionMsg);
                        continue;
                    }
                    StringBuffer pkgName = new StringBuffer(dirPath2pkgName(pkgPath));
                    String folderName = file.getParentFile().getName();
                    String testClassName = StrUtil.upperFirst(StrUtil.toCamelCase(String.format("%sTest",folderName)));
                    pkgName.append(".").append(testClassName);
                    String fullTestClassName = pkgName.toString();
                    log.debug("full test class name is：{},class file is：{},method name is：{}",fullTestClassName,testClassName,fileMainName);
                    TestCaseMeta testCaseInstance =  new TestCaseMeta();
                    testCaseInstance.setMethodName(fileMainName);
                    testCaseInstance.setTestCase(testCase);
                    if(testCasePkgGroup.containsKey(fullTestClassName)){
                        List<TestCaseMeta> testClassList = testCasePkgGroup.get(fullTestClassName);
                        testClassList.add(testCaseInstance);
                    }else{
                        List<TestCaseMeta> testClassList = new ArrayList<>();
                        testClassList.add(testCaseInstance);
                        testCasePkgGroup.put(fullTestClassName,testClassList);
                    }
                }else {
                    log.warn("in pkgPath {} file {} not support,only support .json or .yml suffix",pkgPath,fileMainName);
                }
            }else{
                traversePkgTestCaseGroup(file);
            }
        }
    }

    private static String dirPath2pkgName(String pkgPath){
        StringBuffer pkgName = new StringBuffer();
        pkgName.append(Constant.ROOT_PKG_NAME);
        if(StrUtil.isEmpty(pkgPath)){
            return pkgName.toString();
        }
        pkgName.append(pkgPath.replaceAll("/","."));
        return pkgName.toString();
    }
}

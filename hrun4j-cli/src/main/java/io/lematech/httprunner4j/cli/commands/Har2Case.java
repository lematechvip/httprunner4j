package io.lematech.httprunner4j.cli.commands;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.google.common.collect.Maps;
import io.lematech.httprunner4j.cli.CliConstants;
import io.lematech.httprunner4j.cli.Command;
import io.lematech.httprunner4j.cli.har.HarUtils;
import io.lematech.httprunner4j.cli.har.model.*;
import io.lematech.httprunner4j.common.Constant;
import io.lematech.httprunner4j.entity.http.RequestEntity;
import io.lematech.httprunner4j.entity.testcase.Config;
import io.lematech.httprunner4j.entity.testcase.TestCase;
import io.lematech.httprunner4j.entity.testcase.TestStep;
import io.lematech.httprunner4j.widget.log.MyLog;
import io.lematech.httprunner4j.widget.utils.FilesUtil;
import io.lematech.httprunner4j.widget.utils.JsonUtil;
import org.kohsuke.args4j.Option;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author lematech@foxmail.com
 * @version 1.0.0
 * @className Har2Yml
 * @description The <code>har2case</code> command.
 * @created 2021/4/18 7:53 下午
 * @publicWechat lematech
 */
public class Har2Case extends Command {

    @Option(name = "--file", usage = "Specify the HAR file path.", required = true)
    File harFile;

    @Option(name = "--format", usage = "Generate use case format, support 2y/2j.")
    String format = CliConstants.GENERATE_YML_FORMAT;

    @Option(name = "--case_dir", usage = "Specifies the path  of the generated use case.")
    File generateCaseDirectory;

    @Option(name = "--gen_mode", usage = "Specified generation mode, full/easy mode.")
    String generationMode = CliConstants.GENERATE_MODE_EASY;

    @Option(name = "--filter_suffix", usage = "Filter out the specified request suffix, support multiple suffix formats, multiple in English status ';' division.")
    String filterSuffix;

    @Override
    public String description() {
        return "Print har2yml command information.";
    }

    @Override
    public int execute(PrintWriter out, PrintWriter err) {
        FilesUtil.checkFileExists(harFile);
        if (StrUtil.isEmpty(format) && (!CliConstants.GENERATE_JSON_FORMAT.equalsIgnoreCase(format) || !CliConstants.GENERATE_YML_FORMAT.equalsIgnoreCase(format))) {
            String exceptionMsg = String.format("Specifies the generation format %s exception. Only - 2Y or - 2J format is supported", format);
            MyLog.error(exceptionMsg);
        }
        MyLog.info("Start generating test cases,testcase format:{}", Objects.isNull(format) ? CliConstants.GENERATE_YML_FORMAT : format);
        Har har;
        try {
            har = HarUtils.read(harFile);
        } catch (Exception e) {
            String exceptionMsg = String.format("Error reading HAR file:%s,Exception information:%s", FilesUtil.getCanonicalPath(harFile), e.getMessage());
            MyLog.error(exceptionMsg);
            return 1;
        }
        if (Objects.isNull(har.getLog())) {
            String exceptionMsg = String.format("HAR file %s has no pages!", FilesUtil.getCanonicalPath(harFile));
            MyLog.error(exceptionMsg);
            return 1;
        }
        String workDirPath;
        if (Objects.isNull(generateCaseDirectory)) {
            workDirPath = FilesUtil.getCanonicalPath(new File(Constant.DOT_PATH));
        } else {
            if (!generateCaseDirectory.exists() || !generateCaseDirectory.isDirectory()) {
                String exceptionMsg = String.format("The case directory %s does not exist"
                        , FilesUtil.getCanonicalPath(generateCaseDirectory));
                MyLog.error(exceptionMsg);
                return 1;
            }
            workDirPath = FilesUtil.getCanonicalPath(generateCaseDirectory);
        }
        List<String> filterSuffixList = new ArrayList<>();
        if (Objects.nonNull(filterSuffix)) {
            filterSuffixList = ListUtil.toList(filterSuffix.split(CliConstants.FILTER_REQUEST_SUFFIX_SEPARATOR));
        }
        HarUtils.connectReferences(har, filterSuffixList);
        TestCase testCase = new TestCase();
        Config config = new Config();
        config.setName("Testcase Description");
        config.setVariables(Maps.newHashMap());
        config.setVerify(false);
        List<TestStep> testSteps = new ArrayList<>();
        List<HarPage> harPages = har.getLog().getPages();
        MyLog.info("Number of pages viewed: " + harPages.size());
        for (HarPage page : harPages) {
            MyLog.info(page.toString());
            MyLog.info("Output the calls for this page: ");
            for (HarEntry entry : page.getEntries()) {
                MyLog.info("\t" + entry);
                testSteps.add(buildTestStep(entry));
            }
            testCase.setConfig(config);
            testCase.setTestSteps(testSteps);
            try {
                String caseFileName = FileUtil.getName(harFile);
                File jsonFile;
                JSONObject data = JSONObject.parseObject(JSONObject.toJSONString(testCase, SerializerFeature.PrettyFormat), Feature.OrderedField);
                if (Objects.isNull(format) || format.equalsIgnoreCase(CliConstants.GENERATE_YML_FORMAT)) {
                    jsonFile = new File(workDirPath, String.format("%s.%s", caseFileName, "yml"));
                    DumperOptions dumperOptions = new DumperOptions();
                    dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                    Yaml yaml = new Yaml(dumperOptions);
                    yaml.dump(data, new FileWriter(jsonFile));
                } else {
                    jsonFile = new File(workDirPath, String.format("%s.%s", caseFileName, "json"));
                    JsonUtil.jsonWriteToFile(jsonFile, data);
                }
                MyLog.info("Generated successfully! File path:{}", FilesUtil.getCanonicalPath(jsonFile));
                return 0;
            } catch (IOException ioException) {
                ioException.printStackTrace();
                String exceptionMsg = String.format("\n" +
                        "生成测试用例出现异常，异常信息:\n" +
                        "Exception occurs when generating test cases. Exception information: %s", ioException.getMessage());
                MyLog.error(exceptionMsg);
                return 1;
            }
        }
        return 0;
    }

    private TestStep buildTestStep(HarEntry entry) {
        HarRequest request = entry.getRequest();
        String url = URLUtil.getPath(request.getUrl());
        TestStep testStep = new TestStep();
        testStep.setName(String.format("Request api:%s", url));
        RequestEntity requestEntity = new RequestEntity();
        requestEntity.setMethod(request.getMethod());
        requestEntity.setUrl(request.getUrl());
        //set headers
        List<HarHeader> harRequestHeaders = request.getHeaders();
        Map<String, String> requestHeaders = Maps.newHashMap();
        for (HarHeader harHeader : harRequestHeaders) {
            requestHeaders.put(String.valueOf(harHeader.getName()), harHeader.getValue());
        }
        requestEntity.setHeaders(requestHeaders);
        //set cookie
        List<HarCookie> harRequestCookies = request.getCookies();
        Map<String, Object> requestCookie = Maps.newHashMap();
        for (HarCookie harCookie : harRequestCookies) {
            requestCookie.put(String.valueOf(harCookie.getName()), harCookie.getValue());
        }
        if (MapUtil.isNotEmpty(requestCookie)) {
            requestEntity.setCookies(requestCookie);
        }
        //set get  params
        List<HarQueryParm> queryParams = request.getQueryString();
        Map<String, Object> params = Maps.newHashMap();
        if (queryParams.size() > 0) {
            for (HarQueryParm harQueryParm : queryParams) {
                params.put(String.valueOf(harQueryParm.getName()), harQueryParm.getValue());
            }
        }
        //set post params
        HarPostData harPostData = request.getPostData();
        if (!Objects.isNull(harPostData)) {
            String postContent = harPostData.getText();
            if (JsonUtil.isJson(postContent)) {
                requestEntity.setJson(JSONObject.parseObject(postContent));
            }
            List<HarPostParam> postParams = harPostData.getParams();
            Map<String, Object> postParam = Maps.newHashMap();
            if (Objects.nonNull(postParams) && postParams.size() > 0) {
                for (HarPostParam harPostParam : postParams) {
                    postParam.put(String.valueOf(harPostParam.getName()), harPostParam.getValue());
                }
            }
        }

        //set response headers
        List<Map<String, Object>> validate = new ArrayList<>();
        HarResponse response = entry.getResponse();
        int statusCode = response.getStatus();
        validate.add(buildValidateMap("statusCode", statusCode));
        List<HarHeader> headers = response.getHeaders();
        for (HarHeader harHeader : headers) {
            String headerName = harHeader.getName();
            String headerValue = harHeader.getValue();
            String headerJmesPath = String.format("%s%s%s", Constant.DATA_EXTRACTOR_JMESPATH_HEADERS_START, Constant.DOT_PATH, headerName);
            if (CliConstants.GENERATE_MODE_EASY.equalsIgnoreCase(generationMode)) {
                if (CliConstants.GENERATE_MODE_EASY_CONTENT_TYPE_META.equalsIgnoreCase(headerName)) {
                    validate.add(buildValidateMap(headerJmesPath, headerValue));
                }
            } else if (CliConstants.GENERATE_MODE_FULL.equalsIgnoreCase(generationMode)) {
                validate.add(buildValidateMap(headerJmesPath, headerValue));
            }
        }
        HarContent harContent = response.getContent();
        String mimeType = harContent.getMimeType();
        if (CliConstants.APPLICATION_JSON_MIME_TYPE.equalsIgnoreCase(mimeType)) {
            String content = harContent.getText();
            if (JsonUtil.isJson(content)) {
                JSONObject jsonContent = JSONObject.parseObject(content);
                List<String> defaultItems = new ArrayList<>();
                defaultItems.add("code");
                defaultItems.add("msg");
                buildInJsonContentValidateItem(defaultItems, validate, jsonContent);
            } else {
                MyLog.warn("MIME type returned is application/json, but response content {} cannot be JSON", content);
            }
        }
        testStep.setValidate(validate);
        testStep.setRequest(requestEntity);
        return testStep;
    }

    private void buildInJsonContentValidateItem(List<String> defaultItems, List<Map<String, Object>> validate, JSONObject jsonContent) {
        for (String item : defaultItems) {
            if (jsonContent.containsKey(item)) {
                Object codeValue = jsonContent.get(item);
                validate.add(buildValidateMap(String.format("content.%s", item), codeValue));
            }
        }
    }

    private Map<String, Object> buildValidateMap(String expect, Object actual) {
        List validateMeta = new ArrayList();
        validateMeta.add(expect);
        validateMeta.add(actual);
        Map<String, Object> validateMap = Maps.newHashMap();
        validateMap.put("eq", validateMeta);
        return validateMap;
    }

}
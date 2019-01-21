/**
 * Copyright 2017-2019 European Union, interactive instruments GmbH
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * This work was supported by the EU Interoperability Solutions for
 * European Public Administrations Programme (http://ec.europa.eu/isa)
 * through Action 1.17: A Reusable INSPIRE Reference Platform (ARE3NA).
 */
package de.interactive_instruments.etf.webapp.controller;

import static de.interactive_instruments.etf.webapp.SwaggerConfig.SERVICE_CAP_TAG_NAME;
import static de.interactive_instruments.etf.webapp.SwaggerConfig.TEST_RUNS_TAG_NAME;
import static de.interactive_instruments.etf.webapp.WebAppConstants.API_BASE_URL;
import static de.interactive_instruments.etf.webapp.controller.EtfConfigController.ETF_TEST_RUN_TEMPLATES_ALLOW_CREATION;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.*;

import java.io.IOException;
import java.text.ParseException;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import de.interactive_instruments.etf.dal.dao.Dao;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.IncompleteDtoException;
import de.interactive_instruments.etf.dal.dto.capabilities.TestRunTemplateDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.webapp.conversion.EidConverter;
import de.interactive_instruments.etf.webapp.dto.ApiError;
import de.interactive_instruments.etf.webapp.dto.ApplyTestRunTemplateRequest;
import de.interactive_instruments.etf.webapp.dto.CreateTestRunTemplateRequest;
import de.interactive_instruments.etf.webapp.helpers.SimpleFilter;
import de.interactive_instruments.etf.webapp.helpers.User;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.exceptions.config.InvalidPropertyException;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * Controller for creating Test Run Template and applying them to Test Objects
 */
@RestController
public class TestRunTemplateController {

    @Autowired
    DataStorageService dataStorageService;

    @Autowired
    private TestObjectController testObjectController;

    @Autowired
    private TestDriverController testDriverController;

    @Autowired
    private TestRunController testRunController;

    @Autowired
    private EtfConfigController etfConfig;

    @Autowired
    private StreamingService streaming;

    boolean simplifiedWorkflows;

    private Dao<TestRunTemplateDto> testRunTemplateDao;

    private final static String TEST_RUN_TEMPLATES_URL = API_BASE_URL + "/TestRunTemplates";
    private final Logger logger = LoggerFactory.getLogger(TestRunTemplateController.class);

    private final static String TEST_RUN_TEMPLATE_DESC = "Test Run Templates are a set of "
            + "preselected Executable Test Suites and parameters. The Test Run Template model is described in the "
            + "[XML schema documentation](https://resources.etf-validator.net/schema/v2/doc/capabilities_xsd.html#TestRunTemplate). "
            + ETF_ITEM_COLLECTION_DESCRIPTION;

    public TestRunTemplateController() {}

    @PostConstruct
    public void init() throws ParseException, ConfigurationException, IOException {

        simplifiedWorkflows = "simplified".equals(etfConfig.getProperty(EtfConfigController.ETF_WORKFLOWS));
        testRunTemplateDao = dataStorageService.getDao(TestRunTemplateDto.class);

        logger.info("Test Run Template controller initialized!");
    }

    //
    // Rest interfaces
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @ApiOperation(value = "Get Test Run Template as JSON", notes = TEST_RUN_TEMPLATE_DESC, tags = {
            SERVICE_CAP_TAG_NAME})
    @RequestMapping(value = {TEST_RUN_TEMPLATES_URL + "/{id}",
            TEST_RUN_TEMPLATES_URL + "/{id}.json"}, method = RequestMethod.GET, produces = "application/json")
    public void testObjectByIdJson(@PathVariable String id, @RequestParam(required = false) String search,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, LocalizableApiError, ObjectWithIdNotFoundException {
        streaming.asJson2(testRunTemplateDao, request, response, id);
    }

    @ApiOperation(value = "Get multiple Test Run Templates as JSON", notes = TEST_RUN_TEMPLATE_DESC, tags = {
            SERVICE_CAP_TAG_NAME})
    @RequestMapping(value = {TEST_RUN_TEMPLATES_URL, TEST_RUN_TEMPLATES_URL + ".json"}, method = RequestMethod.GET)
    public void listTestObjectsJson(
            @ApiParam(value = OFFSET_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int offset,
            @ApiParam(value = LIMIT_DESCRIPTION) @RequestParam(required = false, defaultValue = "0") int limit,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
        streaming.asJson2(testRunTemplateDao, request, response, new SimpleFilter(offset, limit));
    }

    @ApiOperation(value = "Get multiple Test Run Templates as XML", notes = TEST_RUN_TEMPLATE_DESC, tags = {
            SERVICE_CAP_TAG_NAME}, produces = "text/xml")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "EtfItemCollection with multiple Test Run Templates")
    })
    @RequestMapping(value = {TEST_RUN_TEMPLATES_URL + ".xml"}, method = RequestMethod.GET)
    public void listTestObjectXml(
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "0") int limit,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, ObjectWithIdNotFoundException {
        streaming.asXml2(testRunTemplateDao, request, response, new SimpleFilter(offset, limit));
    }

    @ApiOperation(value = "Get Test Run Template as XML", notes = TEST_RUN_TEMPLATE_DESC, tags = {
            SERVICE_CAP_TAG_NAME}, produces = "text/xml")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Test Run Template"),
            @ApiResponse(code = 404, message = "Test Run Template not found")
    })
    @RequestMapping(value = {TEST_RUN_TEMPLATES_URL + "/{id}.xml"}, method = RequestMethod.GET)
    public void testObjectByIdXml(
            @ApiParam(value = "ID of Test Run Template that needs to be fetched", example = "EID-1ffe6ea2-5c29-4ce9-9a7e-f4d9d71119e8", required = true) @PathVariable String id,
            HttpServletRequest request, HttpServletResponse response)
            throws IOException, ObjectWithIdNotFoundException, LocalizableApiError {
        streaming.asXml2(testRunTemplateDao, request, response, id);
    }

    @ApiOperation(value = "Check if the Test Run Template exists", notes = TEST_RUN_TEMPLATE_DESC, tags = {
            SERVICE_CAP_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "Test Run Template exists", response = Void.class),
            @ApiResponse(code = 404, message = "Test Run Template does not exist", response = Void.class),
    })
    @RequestMapping(value = {TEST_RUN_TEMPLATES_URL + "/{id}"}, method = RequestMethod.HEAD)
    public ResponseEntity exists(
            @ApiParam(value = "Test Run Template ID. "
                    + EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id) {
        final EID eid = EidConverter.toEid(id);
        return testRunTemplateDao.available(eid) ? new ResponseEntity(HttpStatus.NO_CONTENT)
                : new ResponseEntity(HttpStatus.NOT_FOUND);
    }

    @ApiOperation(value = "Start a new Test Run", notes = "Start a new Test Run by specifying one Test Run Template "
            + "that shall be used to test one Test Object against a set of Executable Test Suites with "
            + "specified test parameters. "
            + "An existing Test Object can be referenced by setting the 'id' in the ApplyTestRunTemplateRequest's "
            + "'testObject' property. "
            + "If data do not need to be uploaded or a web service is tested, a temporary Test Object "
            + "can be created directly with this interface, by defining at least the "
            + "'resources' property of the 'testObject' but then the 'id' property must be omitted."
            + "\n\n"
            + "Example for starting a Test Run with a Test Run Template for service Tests:  <br/>"
            + "\n\n"
            + "    {\n"
            + "        \"label\": \"Test run on 15:00 - 01.01.2017 with all Download Services Conformance Classes\",\n"
            + "        \"testRunTemplate\": [\"EID994edf55-699b-446c-968c-1892a4d8d000\"],\n"
            + "        \"arguments\": {},\n"
            + "        \"testObject\": {\n"
            + "            \"resources\": {\n"
            + "                \"serviceEndpoint\": \"http://example.com/service?request=GetCapabilities&service=WFS\"\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "\n\n"
            + "Example for starting a Test Run with a Test Run Template for a file-based Test, using an existing Test Object:<br/>"
            + "\n\n"
            + "    {\n"
            + "        \"label\": \"Test run on 15:00 - 01.01.2017 with all Metadata Conformance Classes\",\n"
            + "        \"testRunTemplate\": [\"EID942edf55-069b-44a6-12f3-1892a4d8d949\"],\n"
            + "        \"arguments\": {\n"
            + "            \"files_to_test\": \".*\",\n"
            + "            \"tests_to_execute\": \".*\"\n"
            + "        },\n"
            + "        \"testObject\": {\n"
            + "            \"id\": \"8cdd7fab-0c02-4f9e-b957-b40b7d3d22e0\"\n"
            + "        }\n"
            + "    }\n"
            + "\n\n"
            + "Where \"EID8cdd7fab-0c02-4f9e-b957-b40b7d3d22e0\" is the ID of a previously created Test Object. If the "
            + "Test Object does not exist, a 404 error is thrown. The IDs of Temporary Test Objects are not accepted. "
            + "\n\n"
            + "Example for starting a Test Run with a Test Run Template for a file-based Test, , referencing Test data in the web:<br/>"
            + "\n\n"
            + "    {\n"
            + "        \"label\": \"Test run on 15:00 - 01.01.2017 with all Metadata Conformance Classes\",\n"
            + "        \"testRunTemplate\": [\"EID942edf55-069b-44a6-12f3-1892a4d8d949\"],\n"
            + "        \"arguments\": {\n"
            + "            \"files_to_test\": \".*\",\n"
            + "            \"tests_to_execute\": \".*\"\n"
            + "        },\n"
            + "        \"testObject\": {\n"
            + "            \"resources\": {\n"
            + "                \"data\": \"http://example.com/test-data.xml\"\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "\n\n", tags = {TEST_RUNS_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Test Run Template applied"),
            @ApiResponse(code = 400, message = "Invalid request", response = ApiError.class),
            @ApiResponse(code = 404, message = "Test Run Template or Test Object with ID not found", response = ApiError.class),
            @ApiResponse(code = 409, message = "Test Object already in use", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal error", response = ApiError.class),
    })
    @RequestMapping(value = TEST_RUN_TEMPLATES_URL + "/{id}", method = RequestMethod.POST)
    public void start(
            @ApiParam(value = "Test Run Template ID. "
                    + EID_DESCRIPTION, example = EID_EXAMPLE, required = true) @PathVariable String id,
            @RequestBody @Valid ApplyTestRunTemplateRequest testRunTemplateRequest, BindingResult result,
            HttpServletRequest request,
            HttpServletResponse response)
            throws LocalizableApiError, InvalidPropertyException {

        if (result.hasErrors()) {
            throw new LocalizableApiError(result.getFieldError());
        }

        testRunTemplateRequest.init(EidConverter.toEid(id), testRunTemplateDao, testObjectController);
        testRunController.startTestRun(testRunTemplateRequest, request, response);
    }

    @ApiOperation(value = "Create a new Test Run Template", notes = "Please note that this function can be deactivated by the Administrator and the interface then always responds"
            + " with a 403 status code. A Test Run Template bundles a set of Executable Test Suites,"
            + " including their descriptive properties and arguments, and allows them to be applied to test objects. "
            + " The Test Object can be overwritten when the template is applied, or it can be fixed when the "
            + " Test Run Template is created."
            + " If more than one Executable Test Suites is specified during creation, then there must be at least one "
            + " Test Object Type that is supported by all Executable Test Suites."
            + " If a fixed Test Object is provided its type must of course also be supported."
            + " Arguments and descriptive properties for the template are automatically taken from all "
            + " Executable Test Suites. If there are Parameters with the same name but different default values "
            + " in two Executable Test Suites, these must be explicitly overridden."
            + " Properties are only adopted in the Test Run Template if they are identical in all Executable Test Suites. "
            + " Otherwise, they must be specified explicitly."
            + "\n\n"
            + " Example for creating a Test Run Template :<br/>"
            + "\n\n"
            + "    {\n"
            + "        \"label\": \"Metadata Full Conformance\",\n"
            + "        \"defaultParameterValues\": {\n"
            + "            \"tests_to_execute\": \".*\"\n"
            + "        },\n"
            + "        \"executableTestSuiteIds\": [\n"
            + "            \"ec7323d5-d8f0-4cfe-b23a-b826df86d58c\",\n"
            + "            \"9a31ecfc-6673-43c0-9a31-b4595fb53a98\"\n"
            + "        ]\n"
            + "    }\n"
            + "\n\n"
            + "  ", tags = {SERVICE_CAP_TAG_NAME})
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "Test Run Template created"),
            @ApiResponse(code = 400, message = "Invalid request", response = ApiError.class),
            @ApiResponse(code = 403, message = "Test Run Template creation deactivated", response = ApiError.class),
            @ApiResponse(code = 409, message = "Type of the Test Object not supported by the Executable Test Suites or"
                    + " due to a conflict, Parameters with different default values must be explicitly overwritten", response = ApiError.class),
            @ApiResponse(code = 500, message = "Internal error", response = ApiError.class),
    })
    @RequestMapping(value = TEST_RUN_TEMPLATES_URL, method = RequestMethod.PUT)
    public void create(@RequestBody @Valid CreateTestRunTemplateRequest createTestRunTemplateRequest,
            BindingResult result,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException, LocalizableApiError, IncompleteDtoException, ObjectWithIdNotFoundException {

        if (!"true".equals(etfConfig.getProperty(ETF_TEST_RUN_TEMPLATES_ALLOW_CREATION))) {
            throw new LocalizableApiError("l.json.testruntemplate.creation.forbidden", false, 403);
        }
        if (result.hasErrors()) {
            throw new LocalizableApiError(result.getFieldError());
        }

        createTestRunTemplateRequest.init(testDriverController, testObjectController);
        final TestRunTemplateDto testRunTemplate = createTestRunTemplateRequest.toTestRunTemplate();
        testRunTemplate.setAuthor(User.getUser(request));
        testRunTemplate.ensureBasicValidity();
        ((WriteDao<TestRunTemplateDto>) this.testRunTemplateDao).add(testRunTemplate);

        response.setStatus(HttpStatus.CREATED.value());
        streaming.asJson2(testRunTemplateDao, request, response, testRunTemplate.getId().getId());
    }

}

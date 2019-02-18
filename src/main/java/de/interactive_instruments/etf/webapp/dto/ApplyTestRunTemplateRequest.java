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
package de.interactive_instruments.etf.webapp.dto;

import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.*;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.EID_EXAMPLE;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import de.interactive_instruments.etf.dal.dao.PreparedDtoResolver;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestRunTemplateDto;
import de.interactive_instruments.etf.dal.dto.run.TestRunDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.dal.dto.test.ExecutableTestSuiteDto;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.webapp.controller.DataStorageService;
import de.interactive_instruments.etf.webapp.controller.LocalizableApiError;
import de.interactive_instruments.etf.webapp.conversion.EidConverter;
import de.interactive_instruments.exceptions.ObjectWithIdNotFoundException;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@JsonPropertyOrder({
        "testRunTemplateId",
        "label",
        "arguments",
        "testObject"
})
@ApiModel(value = "ApplyTestRunTemplateRequest", description = "Apply a Test Run Template")
public class ApplyTestRunTemplateRequest extends AbstractTestRunRequest {

    @ApiModelProperty(value = EID_DESCRIPTION + ". ", example = EID_EXAMPLE, dataType = "String")
    @JsonProperty(required = true)
    @Pattern(regexp = EidConverter.EID_PATTERN, flags = {Pattern.Flag.CASE_INSENSITIVE})
    private String testRunTemplateId;

    @ApiModelProperty(position = 1, value = TEST_RUN_LABEL_DESCRIPTION
            + " Mandatory.", example = TEST_RUN_LABEL_EXAMPLE, dataType = "String", required = true)
    @JsonProperty(required = true)
    @NotNull(message = "{l.enter.label}")
    private String label;

    @ApiModelProperty(position = 2, value = "Test run arguments as key value pairs. "
            + "Mandatory (use {} for empty arguments). See Implementation Notes for an complete example.", required = true)
    @JsonProperty
    private SimpleArguments arguments;

    @ApiModelProperty(position = 3, value = "Simplified Test Object. This property is mandatory if the Test Run Template does"
            + " not reference a Test Object. If the Test Run Template has a reference, this property is silently ignored!"
            + " The simplified Test Object can either reference an existing Test Object or contain a new"
            + " Test Object definition which references a resource in the web."
            + " See Test Object model for more information and the Implementation Notes for an complete example.")
    @JsonProperty
    private SimpleTestObject testObject;

    @JsonIgnore
    private PreparedDtoResolver<TestRunTemplateDto> testRunTemplateResolver;

    @JsonIgnore
    private PreparedDtoResolver<TestObjectDto> testObjectResolver;

    public ApplyTestRunTemplateRequest() {}

    public ApplyTestRunTemplateRequest(final String label,
            final SimpleArguments arguments,
            final SimpleTestObject testObject) {
        this.label = label;
        this.arguments = arguments;
        this.testObject = testObject;
    }

    @Override
    public TestRunDto toTestRun()
            throws ObjectWithIdNotFoundException, IOException, URISyntaxException {

        final TestRunTemplateDto testRunTemplateDto = testRunTemplateResolver.getById(EidConverter.toEid(testRunTemplateId))
                .getDto();

        final TestObjectDto testObject;
        if (testRunTemplateDto.getTestObjects() == null || testRunTemplateDto.getTestObjects().isEmpty()) {
            if (this.testObject == null) {
                throw new LocalizableApiError("l.json.invalid.test.object", false, 400);
            }
            testObject = this.testObject.toTestObject(testObjectResolver);
        } else {
            testObject = testRunTemplateDto.getTestObjects().get(0);
        }

        final TestRunDto testRun = new TestRunDto();
        testRun.setId(EidFactory.getDefault().createRandomId());
        testRun.setLabel(label);

        for (final ExecutableTestSuiteDto executableTestSuite : testRunTemplateDto.getExecutableTestSuites()) {
            final TestTaskDto testTaskDto = new TestTaskDto();
            testTaskDto.setExecutableTestSuite(executableTestSuite);
            testTaskDto.setTestObject(testObject);
            if (arguments == null || arguments.get().isEmpty()) {
                // FIXME
                testTaskDto.getArguments().setValue("etf.testcases", "*");
            } else {
                for (final Map.Entry<String, String> keyVal : arguments.get().entrySet()) {
                    testTaskDto.getArguments().setValue(keyVal.getKey(), keyVal.getValue());
                }
            }
            if (testObject.getTestObjectTypes() == null) {
                testObject.setTestObjectTypes(
                        testTaskDto.getExecutableTestSuite().getSupportedTestObjectTypes());
            }
            testRun.addTestTask(testTaskDto);
        }
        return testRun;
    }

    public void inject(final DataStorageService dataStorageService) {
        this.testRunTemplateResolver = dataStorageService.getDao(TestRunTemplateDto.class);
        this.testObjectResolver = dataStorageService.getDao(TestObjectDto.class);
        this.testObjectResolver = dataStorageService.getDao(TestObjectDto.class);
    }
}

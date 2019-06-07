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

import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.TEST_OBJECT_LABEL_DESCRIPTION;
import static de.interactive_instruments.etf.webapp.dto.DocumentationConstants.TEST_OBJECT_LABEL_EXAMPLE;

import java.net.URISyntaxException;
import java.util.Map;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.util.HtmlUtils;

import de.interactive_instruments.SUtils;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.model.EidFactory;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@ApiModel(value = "TestObject", description = "A reusable test object")
public class CreateReusableTestObjectRequest {

    @ApiModelProperty(position = 1, value = TEST_OBJECT_LABEL_DESCRIPTION
            + " Mandatory.", example = TEST_OBJECT_LABEL_EXAMPLE, dataType = "String", required = true)
    @JsonProperty(required = true)
    @NotNull(message = "{l.enter.label}")
    private String label;

    @ApiModelProperty(value = "A description for the Test Object. Mandatory.", example = "Partial delivery of spatial data from department X", dataType = "String", required = true)
    @JsonProperty(required = true)
    @NotNull(message = "{l.enter.description}")
    private String description;

    @ApiModelProperty(value = "List of online resources as name / resource pairs. "
            + "Currently two resource types are supported: if a web service resource is defined, the resource name must be 'serviceEndpoint'. "
            + "If a data set resource is defined, the resource name must be 'data'. "
            + "PLEASE NOTE: only the one resource can be used in the current version. "
            + "Either an id or a resource property must be provided.")
    @JsonProperty
    private Map<String, String> resources;

    @ApiModelProperty(value = "Username for resources. "
            + "Optional and only used when the resource property is defined.", example = "FrankDrebin", dataType = "String")
    @JsonProperty
    private String username;

    @ApiModelProperty(value = "Password for resources"
            + "Optional and only used when the resource property is defined.", example = "S3CR3T", dataType = "String")
    @JsonProperty
    private String password;

    public TestObjectDto toTestObject() throws URISyntaxException {
        final TestObjectDto testObject = new TestObjectDto();
        testObject.setId(EidFactory.getDefault().createRandomId());
        testObject.setLabel(this.label);
        testObject.setDescription(this.label);
        testObject.setVersionFromStr("1.0.0");

        if (resources != null && !resources.isEmpty()) {
            for (final Map.Entry<String, String> nameUriEntry : resources.entrySet()) {
                testObject.addResource(new ResourceDto(nameUriEntry.getKey(), HtmlUtils.htmlUnescape(nameUriEntry.getValue())));
            }

            if (!SUtils.isNullOrEmpty(username)) {
                testObject.properties().setProperty("username", username);
                testObject.properties().setProperty("password", password);
            }
        }

        return testObject;
    }
}

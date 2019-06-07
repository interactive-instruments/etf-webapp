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

import static de.interactive_instruments.etf.webapp.controller.TestObjectController.TESTOBJECTS_URL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.web.multipart.MultipartFile;

import de.interactive_instruments.IFile;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@ApiModel(value = "TestObjectCreationResponse", description = "Test Object Creation response")
public class TestObjectCreationResponse {

    static class UploadMetadata {
        @ApiModelProperty(value = "File name", example = "file.xml")
        @JsonProperty
        private final String name;

        @ApiModelProperty(value = "File size in bytes", example = "2048")
        @JsonProperty
        private final String size;

        @ApiModelProperty(value = "File type", example = "text/xml")
        @JsonProperty
        private final String type;

        private UploadMetadata(final String fileName, final long fileSize, final String fileType) {
            this.name = fileName;
            this.size = String.valueOf(fileSize);
            this.type = fileType;
        }
    }

    static class SimplifiedTestObject {
        @JsonProperty(required = false)
        private final String id;

        @JsonProperty(required = false)
        private final String ref;

        private SimplifiedTestObject(final TestObjectDto testObjectDto) {
            if ("true".equals(testObjectDto.properties().getPropertyOrDefault("temporary", "false"))) {
                this.id = testObjectDto.getId().getId();
                this.ref = null;
            } else {
                this.id = null;
                this.ref = TESTOBJECTS_URL + "/" + testObjectDto.getId().getId();
            }
        }
    }

    @ApiModelProperty(value = "Created Test Object")
    @JsonProperty
    private final SimplifiedTestObject testObject;

    @ApiModelProperty(value = "File metadata")
    @JsonProperty
    private final List<UploadMetadata> files;

    @JsonIgnore
    public String getNameForUpload() {
        if (files.size() == 1) {
            return files.get(0).name;
        } else if (files.size() == 2) {
            return files.get(0).name + " and " + files.get(1).name;
        } else if (files.size() > 2) {
            return files.get(0).name + " and " + (files.size() - 1) + " other files";
        } else {
            return "Empty Upload";
        }
    }

    public TestObjectCreationResponse(final TestObjectDto testObject, final Collection<List<MultipartFile>> multipartFiles) {
        this.testObject = new SimplifiedTestObject(testObject);
        this.files = new ArrayList<>();
        for (final List<MultipartFile> multipartFile : multipartFiles) {
            for (final MultipartFile mpf : multipartFile) {
                this.files.add(
                        new UploadMetadata(IFile.sanitize(mpf.getOriginalFilename()), mpf.getSize(), mpf.getContentType()));
            }
        }
    }
}

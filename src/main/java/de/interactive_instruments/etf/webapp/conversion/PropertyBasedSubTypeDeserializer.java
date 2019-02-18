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
package de.interactive_instruments.etf.webapp.conversion;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.interactive_instruments.SUtils;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
public class PropertyBasedSubTypeDeserializer<T> extends StdDeserializer<T> {

    private final Map<String, Class<? extends T>> propertyMappings = new HashMap<>();

    PropertyBasedSubTypeDeserializer(final Class<T> clazz) {
        super(clazz);
    }

    void register(final Class<? extends T> clazz, final String uniqueProperty) {
        propertyMappings.put(uniqueProperty, clazz);
    }

    @Override
    public T deserialize(final JsonParser jp, final DeserializationContext ctxt) throws IOException {

        final ObjectMapper mapper = (ObjectMapper) jp.getCodec();
        final ObjectNode obj = mapper.readTree(jp);
        final Iterator<Entry<String, JsonNode>> elementsIterator = obj.fields();
        Class<? extends T> clazz = null;
        while (elementsIterator.hasNext() && clazz == null) {
            clazz = propertyMappings.get(elementsIterator.next().getKey());
        }
        if (clazz == null) {
            throw ctxt.mappingException("Polymorphic deserialization failed. Expected one property of " +
                    SUtils.concatStr(", ", propertyMappings.keySet()));
        }
        return mapper.treeToValue(obj, clazz);
    }

}

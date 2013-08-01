/*
 * Copyright (C) 2013 Conductor, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.opentsdb.contrib.tsquare.web.view;

import java.io.IOException;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Strings;

/**
 * @author James Royalty (jroyalty) <i>[Jul 25, 2013]</i>
 */
public abstract class AbstractJsonResponseWriter implements DataQueryResponseWriter {
    private static final Logger log = LoggerFactory.getLogger(AbstractJsonResponseWriter.class);
    
    private boolean jsonpAllowed = true;
    private String jsonpRequestParam = "jsonp";
    private String contentType = "application/json";
    
    @Override
    public void beginResponse(final ResponseContext context) throws IOException {
        context.getResponse().setContentType(contentType);
        
        final OutputStream out = context.getResponse().getOutputStream();
        final JsonGenerator json = new JsonFactory().createJsonGenerator(out);
        context.putProperty("jsonGenerator", json);
        
        // By default.
        context.putProperty("isJsonpResponse", Boolean.FALSE);
        
        if (jsonpAllowed) {
            final boolean hasParams = context.getRequest().getParameterMap().containsKey(jsonpRequestParam);
            
            if (hasParams) {
                context.putProperty("isJsonpResponse", Boolean.TRUE);
                
                final String jsonpValue = context.getRequest().getParameter(jsonpRequestParam);
                if (!Strings.isNullOrEmpty(jsonpValue)) {
                    json.writeRaw(jsonpValue);
                }
                
                json.writeRaw('('); // START of jsonp response wrapper.
            }
        }
    }
    
    @Override
    public void endResponse(final ResponseContext context) throws IOException {
        final JsonGenerator jsonGenerator = getJsonGenerator(context);
        
        if (context.getProperty("isJsonpResponse", Boolean.FALSE, Boolean.class)) {
            jsonGenerator.writeRaw(')'); // END of jsonp response wrapper.
        }
    }
    
    protected JsonGenerator getJsonGenerator(final ResponseContext context) {
        return context.getProperty("jsonGenerator", JsonGenerator.class);
    }
    
    public void setJsonpAllowed(boolean jsonpAllowed) {
        this.jsonpAllowed = jsonpAllowed;
    }

    public void setJsonpRequestParam(String jsonpRequestParam) {
        this.jsonpRequestParam = jsonpRequestParam;
    }

    protected void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
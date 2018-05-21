/*
 * Copyright 2016, Stuart Douglas, and individual contributors as indicated
 * by the @authors tag.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.fakereplace.integration.wildfly.autoupdate;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * @author Stuart Douglas
 */
public class ClassLoaderCompilerTestCase {

    @Test
    public void testCompiler() throws Exception {
        try {
            URL baseUrl = getClass().getClassLoader().getResource(".");
            Path path = Paths.get(baseUrl.toURI());
            Path base = path.resolve( ".." + File.separatorChar + ".." + File.separatorChar + "src" + File.separatorChar + "test" + File.separatorChar + "java");
            List<String> data = Collections.singletonList(getClass().getName());
            ClassLoaderCompiler compiler = new ClassLoaderCompiler(new ClassLoader(getClass().getClassLoader()) {
            }, base, data); //the CL will be closed if it is not wrapped
            compiler.compile();
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

}

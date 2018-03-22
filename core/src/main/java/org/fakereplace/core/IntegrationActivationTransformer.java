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

package org.fakereplace.core;

import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.fakereplace.Extension;
import org.fakereplace.ReplaceableClassSelector;
import org.fakereplace.api.ClassChangeAware;
import org.fakereplace.data.InstanceTracker;
import org.fakereplace.replacement.notification.ChangedClassImpl;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.DuplicateMemberException;
import javassist.bytecode.MethodInfo;

/**
 * Transformer that handles Fakereplace plugins
 *
 * @author Stuart Douglas
 */
class IntegrationActivationTransformer implements FakereplaceTransformer {

    private final Map<String, Extension> integrationClassTriggers;

    private final Set<String> loadedClassChangeAwares = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final Set<ClassLoader> integrationClassloader = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    private final List<FakereplaceTransformer> integrationTransformers = new CopyOnWriteArrayList<>();

    private final Set<String> trackedInstances = new HashSet<>();


    IntegrationActivationTransformer(Set<Extension> extension) {
        Map<String, Extension> integrationClassTriggers = new HashMap<>();
        for (Extension i : extension) {
            trackedInstances.addAll(i.getTrackedInstanceClassNames());
            if (i instanceof InternalExtension) {
                List<FakereplaceTransformer> t = ((InternalExtension) i).getTransformers();
                if (t != null) {
                    integrationTransformers.addAll(t);
                }
            }
        }
        for (Extension i : extension) {
            for (String j : i.getIntegrationTriggerClassNames()) {
                integrationClassTriggers.put(j.replace(".", "/"), i);
            }
        }
        this.integrationClassTriggers = integrationClassTriggers;
    }

    @Override
    public boolean transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, ClassFile file, Set<Class<?>> classesToRetransform, ChangedClassImpl changedClass, Set<MethodInfo> modifiedMethods) throws IllegalClassFormatException, BadBytecode, DuplicateMemberException {
        boolean modified = false;
        for (FakereplaceTransformer i : integrationTransformers) {
            if (i.transform(loader, className, classBeingRedefined, protectionDomain, file, classesToRetransform, changedClass, modifiedMethods)) {
                modified = true;
            }
        }

        if (trackedInstances.contains(file.getName())) {
            makeTrackedInstance(file);
            modified = true;
        }

        if (integrationClassTriggers.containsKey(className)) {
            modified = true;
            integrationClassloader.add(loader);
            // we need to load the class in another thread
            // otherwise it will not go through the javaagent
            final Extension extension = integrationClassTriggers.get(className);
            if (!loadedClassChangeAwares.contains(extension.getClassChangeAwareName())) {
                loadedClassChangeAwares.add(extension.getClassChangeAwareName());
                try {
                    Class<?> clazz = Class.forName(extension.getClassChangeAwareName(), true, loader);
                    final Object intance = clazz.newInstance();
                    if (intance instanceof ClassChangeAware) {
                        ClassChangeNotifier.instance().add((ClassChangeAware) intance);
                    }
                    final String replaceableClassSelectorName = extension.getReplaceableClassSelectorName();
                    if (replaceableClassSelectorName != null) {
                        final Class<?> envClass = Class.forName(replaceableClassSelectorName, true, loader);
                        final ReplaceableClassSelector selector = (ReplaceableClassSelector) envClass.newInstance();
                        Fakereplace.addReplaceableClassSelector(selector);
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        return modified;
    }

    public static byte[] getIntegrationClass(ClassLoader c, String name) {
        if (!integrationClassloader.contains(c)) {
            return null;
        }
        URL resource = ClassLoader.getSystemClassLoader().getResource(name.replace('.', '/') + ".class");
        if (resource == null) {
            throw new RuntimeException("Could not load integration class " + name);
        }
        try (InputStream in = resource.openStream()) {
            return org.fakereplace.util.FileReader.readFileBytes(resource.openStream());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * modifies a class so that all created instances are registered with
     * InstanceTracker
     */
    private void makeTrackedInstance(ClassFile file) throws BadBytecode {
        for (MethodInfo m : (List<MethodInfo>) file.getMethods()) {
            if (m.getName().equals("<init>")) {
                Bytecode code = new Bytecode(file.getConstPool());
                code.addLdc(file.getName());
                code.addAload(0);
                code.addInvokestatic(InstanceTracker.class.getName(), "add", "(Ljava/lang/String;Ljava/lang/Object;)V");
                CodeIterator it = m.getCodeAttribute().iterator();
                it.skipConstructor();
                it.insert(code.get());
                m.getCodeAttribute().computeMaxStack();
            }
        }
    }
}

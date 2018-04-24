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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

import org.fakereplace.api.ChangedClass;
import org.fakereplace.api.NewClassData;
import org.fakereplace.logging.Logger;
import org.fakereplace.replacement.notification.ChangedClassImpl;
import org.fakereplace.util.DescriptorUtils;
import javassist.ClassPool;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

/**
 * @author Stuart Douglas
 */
public class MainTransformer implements ClassFileTransformer {

    private static final long INTEGRATION_WAIT_TIME = Long.getLong("org.fakereplace.wait-time", 300);

    private static final Logger log = Logger.getLogger(MainTransformer.class);

    private volatile FakereplaceTransformer[] transformers = {};

    private final List<ChangedClass> changedClasses = new CopyOnWriteArrayList<>();
    private final List<NewClassData> addedClasses = new CopyOnWriteArrayList<>();
    private volatile long integrationTime;
    private final Timer timer = new Timer("Fakereplace integration timer", true);

    /**
     * as some tasks are run asyncronously this allows external agents to wait for them to complete
     */
    private boolean waitingForIntegration;
    private int integrationRun;
    private int retransformationOutstandingCount;

    private volatile boolean retransformationStarted;

    private boolean logClassRetransformation;


    @Override
    public byte[] transform(final ClassLoader loader, final String className, final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain, final byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null) {
            //TODO: deal with lambdas
            return classfileBuffer;
        }
        boolean replaceable = Fakereplace.isClassReplaceable(className, loader);
        if (classBeingRedefined != null) {
            retransformationStarted = true;
            if (logClassRetransformation && replaceable) {
                log.info("Fakereplace is replacing class " + className);
            }
        }
        ChangedClassImpl changedClass = null;
        if (classBeingRedefined != null) {
            changedClass = new ChangedClassImpl(classBeingRedefined, classfileBuffer);
        }

        boolean changed = false;
        if (!replaceable && UnmodifiedFileIndex.isClassUnmodified(className)) {
            return null;
        }
        Set<Class<?>> classesToRetransform = new HashSet<>();
        final ClassFile file;
        try {
            Set<MethodInfo> modifiedMethods = new HashSet<>();
            file = new ClassFile(new DataInputStream(new ByteArrayInputStream(classfileBuffer)));
            for (final FakereplaceTransformer transformer : transformers) {
                if (transformer.transform(loader, className, classBeingRedefined, protectionDomain, file, classesToRetransform, changedClass, modifiedMethods, replaceable)) {
                    changed = true;
                }
            }
            if (!changed) {
                UnmodifiedFileIndex.markClassUnmodified(className);
                return null;
            } else {
                try {
                    if (!modifiedMethods.isEmpty()) {
                        ClassPool classPool = new ClassPool();
                        if(loader == null) {
                            classPool.appendClassPath(new LoaderClassPath(ClassLoader.getSystemClassLoader()));
                        } else {
                            classPool.appendClassPath(new LoaderClassPath(loader));
                        }
                        classPool.appendSystemPath();
                        for (MethodInfo method : modifiedMethods) {
                            if (method.getCodeAttribute() != null) {
                                method.getCodeAttribute().computeMaxStack();
                                try {
                                    method.rebuildStackMap(classPool);
                                } catch (BadBytecode e) {
                                    Throwable root = e;
                                    while (!(root instanceof NotFoundException) && root != null && root.getCause() != root) {
                                        root = root.getCause();
                                    }

                                    if (root instanceof NotFoundException) {
                                        NotFoundException cause = (NotFoundException) root;
                                        Bytecode bytecode = new Bytecode(file.getConstPool());
                                        bytecode.addNew(NoClassDefFoundError.class.getName());
                                        bytecode.add(Opcode.DUP);
                                        bytecode.addLdc(cause.getMessage());
                                        bytecode.addInvokespecial(NoClassDefFoundError.class.getName(), "<init>", "(Ljava/lang/String;)V");
                                        bytecode.add(Opcode.ATHROW);
                                        method.setCodeAttribute(bytecode.toCodeAttribute());
                                        method.getCodeAttribute().computeMaxStack();
                                        method.getCodeAttribute().setMaxLocals(DescriptorUtils.maxLocalsFromParameters(method.getDescriptor()) + 1);
                                        method.rebuildStackMap(classPool);
                                    } else {
                                        throw e;
                                    }
                                }
                            }
                        }
                    }
                } catch (BadBytecode e) {

                    ByteArrayOutputStream bs = new ByteArrayOutputStream();
        `            file.write(new DataOutputStream(bs));
                    // dump the class for debugging purposes
                    final String dumpDir = AgentOptions.getOption(AgentOption.DUMP_DIR);
                    if (dumpDir != null) {
                        try {
                            File dump = new File(dumpDir + '/' + file.getName() + "$FAILED.class");
                            dump.getParentFile().mkdirs();
                            FileOutputStream s = new FileOutputStream(dump);
                            DataOutputStream dos = new DataOutputStream(s);
                            file.write(dos);
                            s.close();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    throw new RuntimeException(e);
                }
                ByteArrayOutputStream bs = new ByteArrayOutputStream();
                file.write(new DataOutputStream(bs));
                // dump the class for debugging purposes
                final String dumpDir = AgentOptions.getOption(AgentOption.DUMP_DIR);
                if (dumpDir != null) {
                    try {
                        File dump = new File(dumpDir + '/' + file.getName() + ".class");
                        dump.getParentFile().mkdirs();
                        FileOutputStream s = new FileOutputStream(dump);
                        DataOutputStream dos = new DataOutputStream(s);
                        file.write(dos);
                        s.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (!classesToRetransform.isEmpty()) {
                    synchronized (this) {
                        retransformationOutstandingCount++;
                    }
                    Thread t = new Thread(() -> {
                        try {
                            Fakereplace.getInstrumentation().retransformClasses(classesToRetransform.toArray(new Class[classesToRetransform.size()]));
                        } catch (UnmodifiableClassException e) {
                            log.error("Failed to retransform classes", e);
                        } finally {
                            synchronized (MainTransformer.this) {
                                retransformationOutstandingCount--;
                                notifyAll();
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }

                if (classBeingRedefined != null) {
                    changedClasses.add(changedClass);
                    queueIntegration();
                }
                return bs.toByteArray();
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new IllegalClassFormatException(e.getMessage());
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void queueIntegration() {
        //retransformed classes should trigger this as well
        synchronized (this) {
            if (!waitingForIntegration) {
                waitingForIntegration = true;
            }
            integrationTime = System.currentTimeMillis() + INTEGRATION_WAIT_TIME;
            timer.schedule(new IntegrationTask(integrationRun), INTEGRATION_WAIT_TIME);
        }
    }

    public synchronized void addTransformer(FakereplaceTransformer transformer) {
        final FakereplaceTransformer[] transformers = new FakereplaceTransformer[this.transformers.length + 1];
        System.arraycopy(this.transformers, 0, transformers, 0, this.transformers.length);
        transformers[this.transformers.length] = transformer;
        this.transformers = transformers;
    }

    public synchronized void removeTransformer(FakereplaceTransformer transformer) {
        final FakereplaceTransformer[] transformers = new FakereplaceTransformer[this.transformers.length - 1];
        int j = 0;
        for (int i = 0; i < this.transformers.length; ++i) {
            FakereplaceTransformer value = this.transformers[i];
            if (value != transformer) {
                transformers[++j] = this.transformers[i];
            }
        }
        this.transformers = transformers;
    }

    public void runIntegration() {
        System.out.println("Running Integration");
        try {
            List<ChangedClass> changes;
            List<NewClassData> added;
            synchronized (this) {
                changes = new ArrayList<>(changedClasses);
                changedClasses.clear();
                added = new ArrayList<>(addedClasses);
                addedClasses.clear();
            }
            if (!changes.isEmpty() || !added.isEmpty()) {
                ClassChangeNotifier.instance().afterChange(changes, added);
            }
        } finally {
            synchronized (this) {
                waitingForIntegration = false;
                integrationRun++;
                notifyAll();
            }
        }
    }

    public synchronized void addNewClass(NewClassData newClassData) {
        addedClasses.add(newClassData);
        queueIntegration();

    }

    public void waitForTasks() {
        synchronized (this) {
            while (waitingForIntegration) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private class IntegrationTask extends TimerTask {
        private final int integrationRun;
        public IntegrationTask(int integrationRun) {
            this.integrationRun = integrationRun;
        }

        @Override
        public void run() {
            if (System.currentTimeMillis() < integrationTime) {
                return;
            }
            synchronized (MainTransformer.this) {
                if (retransformationOutstandingCount > 0) {
                    return;
                }
                if(this.integrationRun != MainTransformer.this.integrationRun) {
                    return;
                }
            }
            runIntegration();
        }
    }

    public boolean isLogClassRetransformation() {
        return logClassRetransformation;
    }

    public void setLogClassRetransformation(boolean logClassRetransformation) {
        this.logClassRetransformation = logClassRetransformation;
    }

    public boolean isRetransformationStarted() {
        return retransformationStarted;
    }

    public void setRetransformationStarted(boolean retransformationStarted) {
        this.retransformationStarted = retransformationStarted;
    }
}

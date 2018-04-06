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

package org.fakereplace.manip;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fakereplace.core.Fakereplace;
import org.fakereplace.core.Transformer;
import org.fakereplace.data.BaseClassData;
import org.fakereplace.data.ClassDataStore;
import org.fakereplace.data.FieldData;
import org.fakereplace.logging.Logger;
import org.fakereplace.util.Boxing;
import org.fakereplace.runtime.FieldDataStore;
import org.fakereplace.runtime.FieldReferenceDataStore;
import org.fakereplace.util.DescriptorUtils;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

public class FieldManipulator implements ClassManipulator {

    private static final String FIELD_DATA_STORE_CLASS = FieldDataStore.class.getName();

    private static final Logger log = Logger.getLogger(FieldManipulator.class);

    /**
     * added field information by class
     */
    private final ManipulationDataStore<Data> data = new ManipulationDataStore<>();

    public void addField(int arrayIndex, String name, String descriptor, String className, ClassLoader classLoader) {
        data.add(className, new Data(arrayIndex, name, descriptor, className, classLoader));
    }

    public boolean transformClass(ClassFile file, ClassLoader loader, boolean modifiableClass, final Set<MethodInfo> modifiedMethods, boolean replaceable) {
        Map<String, Set<Data>> addedFieldData = data.getManipulationData(loader);
        if (addedFieldData.isEmpty()) {
            return false;
        }
        Map<Integer, Data> fieldAccessLocations = new HashMap<>();
        // first we need to scan the constant pool looking for
        // CONST_Fieldref structures
        ConstPool pool = file.getConstPool();
        for (int i = 1; i < pool.getSize(); ++i) {
            // we have a field reference
            if (pool.getTag(i) == ConstPool.CONST_Fieldref) {
                String className = pool.getFieldrefClassName(i);
                String fieldName = pool.getFieldrefName(i);
                String descriptor = pool.getFieldrefType(i);
                boolean handled = false;
                if (addedFieldData.containsKey(className)) {
                    for (Data data : addedFieldData.get(className)) {
                        if (fieldName.equals(data.getName())) {
                            // store the location in the const pool of the method ref
                            fieldAccessLocations.put(i, data);
                            handled = true;
                            break;
                        }

                    }
                }
                if (!handled && replaceable) {
                    //may be an added field
                    //if the field does not actually exist yet we just assume it is about to come into existence
                    //and rewrite it anyway
                    BaseClassData data = ClassDataStore.instance().getBaseClassData(loader, className);
                    if(data != null) {
                        FieldData field = data.getField(fieldName);
                        if (field == null) {
                            //this is a new field
                            //lets deal with it
                            int fieldNo = FieldReferenceDataStore.instance().getFieldNo(fieldName, descriptor);
                            Data fieldData = new Data(fieldNo, fieldName, descriptor, className, loader);
                            fieldAccessLocations.put(i, fieldData);
                            Transformer.getManipulator().rewriteInstanceFieldAccess(fieldNo, fieldName, descriptor, className, loader);
                            addedFieldData = this.data.getManipulationData(loader);

                        }
                    }
                }
            }
        }

        // this means we found an instance of the call, now we have to iterate
        // through the methods and replace instances of the call
        if (!fieldAccessLocations.isEmpty()) {
            List<MethodInfo> methods = file.getMethods();
            for (MethodInfo m : methods) {
                try {
                    // ignore abstract methods
                    if (m.getCodeAttribute() == null) {
                        continue;
                    }
                    CodeIterator it = m.getCodeAttribute().iterator();
                    while (it.hasNext()) {
                        // loop through the bytecode
                        int index = it.next();
                        int op = it.byteAt(index);
                        // if the bytecode is a field access
                        if (op == Opcode.PUTFIELD || op == Opcode.GETFIELD || op == Opcode.GETSTATIC || op == Opcode.PUTSTATIC) {
                            int val = it.s16bitAt(index + 1);
                            // if the field access is for an added field
                            if (fieldAccessLocations.containsKey(val)) {
                                Data data = fieldAccessLocations.get(val);
                                int arrayPos = file.getConstPool().addIntegerInfo(data.getArrayIndex());
                                // write over the field access with nop
                                it.writeByte(Opcode.NOP, index);
                                it.writeByte(Opcode.NOP, index + 1);
                                it.writeByte(Opcode.NOP, index + 2);

                                if (op == Opcode.PUTFIELD) {
                                    Bytecode b = new Bytecode(file.getConstPool());
                                    if (data.getDescriptor().charAt(0) != 'L' && data.getDescriptor().charAt(0) != '[') {
                                        Boxing.box(b, data.getDescriptor().charAt(0));
                                    }
                                    b.addLdc(arrayPos);
                                    b.addInvokestatic(FIELD_DATA_STORE_CLASS, "setValue", "(Ljava/lang/Object;Ljava/lang/Object;I)V");
                                    it.insertEx(b.get());
                                } else if (op == Opcode.GETFIELD) {
                                    Bytecode b = new Bytecode(file.getConstPool());
                                    b.addLdc(arrayPos);
                                    b.addInvokestatic(FIELD_DATA_STORE_CLASS, "getValue", "(Ljava/lang/Object;I)Ljava/lang/Object;");

                                    if (DescriptorUtils.isPrimitive(data.getDescriptor())) {
                                        Boxing.unbox(b, data.getDescriptor().charAt(0));
                                    } else {
                                        b.addCheckcast(DescriptorUtils.getTypeStringFromDescriptorFormat(data.getDescriptor()));
                                    }
                                    it.insertEx(b.get());
                                } else if (op == Opcode.PUTSTATIC) {
                                    Bytecode b = new Bytecode(file.getConstPool());
                                    if (data.getDescriptor().charAt(0) != 'L' && data.getDescriptor().charAt(0) != '[') {
                                        Boxing.box(b, data.getDescriptor().charAt(0));
                                    }
                                    b.addLdc(file.getConstPool().addClassInfo(data.getClassName()));
                                    b.add(Opcode.SWAP);
                                    b.addLdc(arrayPos);
                                    b.addInvokestatic(FIELD_DATA_STORE_CLASS, "setValue", "(Ljava/lang/Object;Ljava/lang/Object;I)V");
                                    it.insertEx(b.get());
                                } else if (op == Opcode.GETSTATIC) {
                                    Bytecode b = new Bytecode(file.getConstPool());
                                    b.addLdc(file.getConstPool().addClassInfo(data.getClassName()));
                                    b.addLdc(arrayPos);
                                    b.addInvokestatic(FIELD_DATA_STORE_CLASS, "getValue", "(Ljava/lang/Object;I)Ljava/lang/Object;");

                                    if (DescriptorUtils.isPrimitive(data.getDescriptor())) {
                                        Boxing.unbox(b, data.getDescriptor().charAt(0));
                                    } else {
                                        b.addCheckcast(DescriptorUtils.getTypeStringFromDescriptorFormat(data.getDescriptor()));
                                    }
                                    it.insertEx(b.get());
                                }
                                modifiedMethods.add(m);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Bad byte code transforming " + file.getName(), e);
                    e.printStackTrace();
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public void clearRewrites(String className, ClassLoader loader) {
        data.remove(className, loader);
    }

    /**
     * Stores information about an added instance field.
     *
     * @author stuart
     */
    private static class Data implements ClassLoaderFiltered<Data> {
        private final int arrayIndex;
        private final String name;
        private final String descriptor;
        private final String className;
        private final ClassLoader classLoader;

        public Data(int arrayIndex, String name, String descriptor, String className, ClassLoader classLoader) {
            super();
            this.arrayIndex = arrayIndex;
            this.name = name;
            this.descriptor = descriptor;
            this.className = className;
            this.classLoader = classLoader;
        }

        public int getArrayIndex() {
            return arrayIndex;
        }

        public String getName() {
            return name;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public String getClassName() {
            return className;
        }

        public ClassLoader getClassLoader() {
            return classLoader;
        }

        public Data getInstance() {
            return this;
        }

    }
}

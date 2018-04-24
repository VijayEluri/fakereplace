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

package a.org.fakereplace.test.replacement.virtualmethod;

public class VirtualMethodExceptionClass1 {
    int a = 0;

    public int doStuff1(int param1, int param2) {
        try {
            if (a == 0) {
                throw new Exception();
            }
            return 1;
        } catch (Exception e) {

        }
        return 1;
    }

    public int doStuff2(int param1, int param2) {
        try {
            if (a == 0) {
                throw new Exception();
            }
            return 1;
        } catch (Exception e) {

        }
        return 1;
    }



    public int doStuff3(int param1, int param2, long anotherPram) {
        int count = 1;
        try {
            count++;
            if (a == 0) {
                throw new Exception();
            }
            count++;
            String s = new String("dfg");
            System.out.println(s);
        } catch (Exception e) {
            count = 100;
        }

        return count;
    }
}

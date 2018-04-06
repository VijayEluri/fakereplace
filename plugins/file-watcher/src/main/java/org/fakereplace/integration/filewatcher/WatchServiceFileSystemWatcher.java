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

package org.fakereplace.integration.filewatcher;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.nio.file.SensitivityWatchEventModifier;

public class WatchServiceFileSystemWatcher implements Runnable, AutoCloseable {

    private static final AtomicInteger threadIdCounter = new AtomicInteger(0);
    private static final int WAIT_TIME = Integer.getInteger("fakereplace.wait-time", 500);
    private static final String THREAD_NAME = "fakereplace-file-watcher";

    private WatchService watchService;
    private final Map<Path, PathData> files = Collections.synchronizedMap(new HashMap<>());
    private final Map<WatchKey, PathData> pathDataByKey = Collections.synchronizedMap(new IdentityHashMap<>());

    private volatile boolean stopped = false;
    private final Thread watchThread;

    public WatchServiceFileSystemWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        watchThread = new Thread(this, THREAD_NAME + threadIdCounter);
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                final WatchKey key = watchService.take();
                if (key != null) {
                    try {
                        PathData pathData = pathDataByKey.get(key);
                        if (pathData != null) {
                            List<WatchEvent<?>> events = new ArrayList<>(key.pollEvents());
                            final List<FileChangeEvent> results = new ArrayList<>();
                            List<WatchEvent<?>> latest;
                            do {
                                //we need to wait till nothing has changed in 500ms to make sure we have picked up all the changes
                                Thread.sleep(WAIT_TIME);
                                latest = key.pollEvents();
                                events.addAll(latest);
                            } while (!latest.isEmpty());
                            final Set<Path> addedFiles = new HashSet<>();
                            final Set<Path> deletedFiles = new HashSet<>();
                            for (WatchEvent<?> event : events) {
                                Path eventPath = (Path) event.context();
                                Path targetFile = ((Path) key.watchable()).resolve(eventPath);
                                FileChangeEvent.Type type;

                                if (event.kind() == ENTRY_CREATE) {
                                    type = FileChangeEvent.Type.ADDED;
                                    addedFiles.add(targetFile);
                                    if (Files.isDirectory(targetFile)) {
                                        try {
                                            addWatchedDirectory(pathData, targetFile);
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                } else if (event.kind() == ENTRY_MODIFY) {
                                    type = FileChangeEvent.Type.MODIFIED;
                                } else if (event.kind() == ENTRY_DELETE) {
                                    type = FileChangeEvent.Type.REMOVED;
                                    deletedFiles.add(targetFile);
                                } else {
                                    continue;
                                }
                                results.add(new FileChangeEvent(targetFile, type));
                            }
                            key.pollEvents().clear();

                            //now we need to prune the results, to remove duplicates
                            //e.g. if the file is modified after creation we only want to
                            //show the create event
                            final List<FileChangeEvent> newEvents = new ArrayList<>();
                            Iterator<FileChangeEvent> it = results.iterator();
                            while (it.hasNext()) {
                                FileChangeEvent event = it.next();
                                boolean added = addedFiles.contains(event.getFile());
                                boolean deleted = deletedFiles.contains(event.getFile());
                                if (event.getType() == FileChangeEvent.Type.MODIFIED) {
                                    if (added || deleted) {
                                        it.remove();
                                    }
                                } else if (event.getType() == FileChangeEvent.Type.ADDED) {
                                    if (deleted) {
                                        it.remove();
                                        newEvents.add(new FileChangeEvent(event.getFile(), FileChangeEvent.Type.MODIFIED)); //if it was both deleted and added it was modified
                                    }
                                } else if (event.getType() == FileChangeEvent.Type.REMOVED) {
                                    if (added) {
                                        it.remove();
                                    }
                                }
                            }
                            results.addAll(newEvents);

                            if (!results.isEmpty()) {
                                for (FileChangeCallback callback : pathData.callbacks) {
                                    invokeCallback(callback, results);
                                }
                            }
                        }
                    } finally {
                        //if the key is no longer valid remove it from the files list
                        if (!key.reset()) {
                            files.remove(key.watchable());
                        }
                    }
                }
            } catch (InterruptedException e) {
                //ignore
            } catch (ClosedWatchServiceException cwse) {
                // the watcher service is closed, so no more waiting on events
                // @see https://developer.jboss.org/message/911519
                break;
            }
        }
    }

    public synchronized void watchPath(Path path, FileChangeCallback callback) {
        try {
            PathData data = files.get(path);
            if (data == null) {
                Set<Path> allDirectories = doScan(path).keySet();
                data = new PathData(path);
                for (Path dir : allDirectories) {
                    addWatchedDirectory(data, dir);
                }
                files.put(path, data);
            }
            data.callbacks.add(callback);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addWatchedDirectory(PathData data, Path dir) throws IOException {
        WatchKey key = dir.register(watchService, new WatchEvent.Kind[] {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY}, SensitivityWatchEventModifier.HIGH);
        pathDataByKey.put(key, data);
        data.keys.add(key);
    }

    public synchronized void unwatchPath(Path file, final FileChangeCallback callback) {
        PathData data = files.get(file);
        if (data != null) {
            data.callbacks.remove(callback);
            if (data.callbacks.isEmpty()) {
                files.remove(file);
                for (WatchKey key : data.keys) {
                    key.cancel();
                    pathDataByKey.remove(key);
                }

            }
        }
    }

    @Override
    public void close() throws IOException {
        this.stopped = true;
        watchThread.interrupt();
        if (watchService != null) {
            watchService.close();
        }
    }


    private static Map<Path, Long> doScan(Path file) throws IOException {
        final Map<Path, Long> results = new HashMap<>();

        final Deque<Path> toScan = new ArrayDeque<>();
        toScan.add(file);
        while (!toScan.isEmpty()) {
            Path next = toScan.pop();
            if (Files.isDirectory(next)) {
                results.put(next, Files.getLastModifiedTime(next).toMillis());
                Stream<Path> list = Files.list(next);
                if (list != null) {
                    list.forEach((toScan::push));
                }
            }
        }
        return results;
    }

    private static void invokeCallback(FileChangeCallback callback, List<FileChangeEvent> results) {
        try {
            callback.handleChanges(results);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class PathData {
        final Path path;
        final List<FileChangeCallback> callbacks = new ArrayList<>();
        final List<WatchKey> keys = new ArrayList<>();

        private PathData(Path path) {
            this.path = path;
        }
    }


    /**
     * The event object that is fired when a file system change is detected.
     *
     * @author Stuart Douglas
     */
    public static class FileChangeEvent {

        private final Path file;
        private final Type type;

        /**
         * Construct a new instance.
         *
         * @param file the file which is being watched
         * @param type the type of event that was encountered
         */
        public FileChangeEvent(Path file, Type type) {
            this.file = file;
            this.type = type;
        }

        /**
         * Get the file which was being watched.
         *
         * @return the file which was being watched
         */
        public Path getFile() {
            return file;
        }

        /**
         * Get the type of event.
         *
         * @return the type of event
         */
        public Type getType() {
            return type;
        }

        /**
         * Watched file event types.  More may be added in the future.
         */
        public enum Type {
            /**
             * A file was added in a directory.
             */
            ADDED,
            /**
             * A file was removed from a directory.
             */
            REMOVED,
            /**
             * A file was modified in a directory.
             */
            MODIFIED,
        }

    }

    /**
     * Callback for file system change events
     *
     * @author Stuart Douglas
     */
    public interface FileChangeCallback {

        /**
         * Method that is invoked when file system changes are detected.
         *
         * @param changes the file system changes
         */
        void handleChanges(final Collection<FileChangeEvent> changes);

    }

}

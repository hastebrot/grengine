/*
   Copyright 2014-now by Alain Stalder. Made in Switzerland.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package ch.grengine.engine;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import ch.grengine.code.ClassNameConflictAnalyzer;
import ch.grengine.code.Code;
import ch.grengine.except.ClassNameConflictException;
import ch.grengine.except.CompileException;
import ch.grengine.except.LoadException;
import ch.grengine.load.DefaultTopCodeCacheFactory;
import ch.grengine.load.LayeredClassLoader;
import ch.grengine.load.LoadMode;
import ch.grengine.load.SourceClassLoader;
import ch.grengine.load.TopCodeCache;
import ch.grengine.load.TopCodeCacheFactory;
import ch.grengine.source.Source;
import ch.grengine.sources.Sources;


/**
 * Engine based on instances of {@link LayeredClassLoader}.
 * 
 * @since 1.0
 * 
 * @author Alain Stalder
 * @author Made in Switzerland.
 */
public class LayeredEngine implements Engine {
    
    private final Builder builder;

    private final EngineId engineId = new EngineId();
    
    private final boolean isWithTopCodeCache;
    private final TopCodeCache topCodeCache;
    
    // default loader (number 0)
    private final Loader loader;
    // next loader number to use
    private long nextLoaderNumber;
    // map of all attached loaders created by this engine,
    // except that loaders that are no longer referenced outside this map
    // can be garbage collected by the VM, since this is a WeakHashMap
    // (note that the map value is not used at all and does not matter
    // for garbage collection of map entries, only the map key does)
    private final Map<Loader,EngineId> attachedLoaders = new WeakHashMap<Loader,EngineId>();
    
    private final Lock read;
    private final Lock write;
    
    /**
     * constructor from builder.
     * 
     * @since 1.0
     */
    protected LayeredEngine(final Builder builder) {
        this.builder = builder.commit();
        
        isWithTopCodeCache = builder.isWithTopCodeCache();
        if (isWithTopCodeCache) {
            topCodeCache = builder.getTopCodeCacheFactory().newTopCodeCache(null);
        } else {
            topCodeCache = null;
        }
        LayeredClassLoader layeredClassLoader = newLayeredClassLoaderFromCodeLayers(new LinkedList<Code>());
        if (isWithTopCodeCache) {
            topCodeCache.setParent(layeredClassLoader);
        }
        
        nextLoaderNumber = 0;
        loader = new Loader(engineId, nextLoaderNumber++, true, layeredClassLoader);
        attachedLoaders.put(loader, engineId);
        
        ReadWriteLock lock = new ReentrantReadWriteLock();
        read = lock.readLock();
        write = lock.writeLock();
    }
    
    private LayeredClassLoader newLayeredClassLoaderFromCodeLayers(final List<Code> codeLayers) {
        return new LayeredClassLoader.Builder()
                .setParent(builder.getParent())
                .setLoadMode(builder.getLoadMode())
                .setCodeLayers(codeLayers)
                .setWithTopCodeCache(isWithTopCodeCache, topCodeCache)
                .setTopLoadMode(builder.getTopLoadMode())
                .buildFromCodeLayers();
    }
    
    private LayeredClassLoader newLayeredClassLoaderFromSourceSetLayers(final List<Sources> sourcesLayers) 
            throws CompileException {
        return new LayeredClassLoader.Builder()
                .setParent(builder.getParent())
                .setLoadMode(builder.getLoadMode())
                .setSourcesLayers(sourcesLayers)
                .setWithTopCodeCache(isWithTopCodeCache, topCodeCache)
                .setTopLoadMode(builder.getTopLoadMode())
                .buildFromSourcesLayers();
    }

    @Override
    public Loader getLoader() {
        return loader;
    }
    
    /**
     * creates and gets a new attached loader, backed by the same bytecode
     * as all other shared loaders created by this engine,
     * with a top code cache shared with all other attached loaders
     * of this engine and automatically updated if code layers are set.
     * 
     * @since 1.0
     */
    @Override
    public Loader newAttachedLoader() {
        write.lock();
        try {
            Loader newLoader = new Loader(engineId, nextLoaderNumber++, true,
                    loader.getSourceClassLoader(engineId).clone());
            attachedLoaders.put(newLoader, engineId);
            return newLoader;
        } finally {
            write.unlock();
        }
    }
    
    /**
     * creates and gets a new detached loader, backed initially by the same
     * bytecode as all attached loaders created by this engine (code layers
     * and top code cache), but not updated if code layers are set
     * and with a separate top code cache instance.
     * <p>
     * For example, a web application might create a detached loader for
     * each new HTTP session: A new loader in order to separate static
     * variables of scripts between sessions (security feature); a detached
     * loader in order to keep code layers constant during the lifetime
     * of the session (consistent behavior of Groovy script calls).
     * 
     * @since 1.0
     */
    @Override
    public Loader newDetachedLoader() {
        write.lock();
        try {
            LayeredClassLoader layeredClassLoader = ((LayeredClassLoader)loader.getSourceClassLoader(engineId));
            Loader newLoader = new Loader(engineId, nextLoaderNumber++, false,
                    layeredClassLoader.cloneWithSeparateTopCodeCache());
            return newLoader;
        } finally {
            write.unlock();
        }
    }
    
    private SourceClassLoader getSourceClassLoader(final Loader loader) {
        read.lock();
        try {
            return loader.getSourceClassLoader(engineId);
        } finally {
            read.unlock();
        }
        
    }
    
    @Override
    public Class<?> loadMainClass(final Loader loader, final Source source)
            throws CompileException, LoadException {
        return getSourceClassLoader(loader).loadMainClass(source);
    }
    
    @Override
    public Class<?> loadClass(final Loader loader, final Source source, final String name)
            throws CompileException, LoadException {
        return getSourceClassLoader(loader).loadClass(source, name);
    }
    
    @Override
    public Class<?> loadClass(final Loader loader, final String name) throws LoadException {
        try {
            return getSourceClassLoader(loader).loadClass(name);
        } catch (Throwable t) {
            throw new LoadException("Could not load class '" + name + "'.", t);
        }
    }
            
    @Override
    public void setCodeLayers(final List<Code> codeLayers) throws ClassNameConflictException {
        if (codeLayers == null) {
            throw new IllegalArgumentException("Code layers are null.");
        }
        
        int nConflicts = 0;
        Map<String,List<Code>> sameClassNamesInMultipleCodeLayersMap = null;
        if (!builder.isAllowSameClassNamesInMultipleCodeLayers()) {
            sameClassNamesInMultipleCodeLayersMap =
                    ClassNameConflictAnalyzer.getSameClassNamesInMultipleCodeLayersMap(codeLayers);
            nConflicts += sameClassNamesInMultipleCodeLayersMap.size();
        }
        Map<String,List<Code>> sameClassNamesInParentAndCodeLayersMap = null;
        if (!builder.isAllowSameClassNamesInParentAndCodeLayers()) {
            sameClassNamesInParentAndCodeLayersMap =
                    ClassNameConflictAnalyzer.getSameClassNamesInParentAndCodeLayersMap(builder.getParent(), codeLayers);
            nConflicts += sameClassNamesInParentAndCodeLayersMap.size();
        }
        if (nConflicts > 0) {
            throw new ClassNameConflictException("Found " + nConflicts + " class name conflict(s).",
                    sameClassNamesInMultipleCodeLayersMap, sameClassNamesInParentAndCodeLayersMap);
        }
        
        write.lock();
        try {
            Map<Loader,EngineId> attachedLoadersNonWeak = new HashMap<Loader,EngineId>();
            attachedLoadersNonWeak.putAll(attachedLoaders);
            for (Loader attachedLoader : attachedLoadersNonWeak.keySet()) {
                attachedLoader.setSourceClassLoader(engineId, newLayeredClassLoaderFromCodeLayers(codeLayers));
            }
            if (isWithTopCodeCache) {
                topCodeCache.setParent(loader.getSourceClassLoader(engineId));
            }
        } finally {
            write.unlock();
        }
    }
    
    @Override
    public void setCodeLayersBySource(final List<Sources> sourcesLayers)
            throws CompileException, ClassNameConflictException {
        if (sourcesLayers == null) {
            throw new IllegalArgumentException("Sources layers are null.");
        }
        setCodeLayers(newLayeredClassLoaderFromSourceSetLayers(sourcesLayers).getCodeLayers());
    }
    
    /**
     * gets the builder.
     * 
     * @since 1.0
     */
    public Builder getBuilder() {
        return builder;
    }
    
    /**
     * Builder for instances of {@link LayeredEngine}.
     * 
     * @since 1.0
     * 
     * @author Alain Stalder
     * @author Made in Switzerland.
     */
    public static class Builder {
        
        private boolean isCommitted;
        
        private ClassLoader parent;
        private LoadMode loadMode;
        
        private boolean isWithTopCodeCache = true;
        private TopCodeCacheFactory topCodeCacheFactory;  
        private LoadMode topLoadMode;
        
        private boolean allowSameClassNamesInMultipleCodeLayers = true;
        private boolean allowSameClassNamesInParentAndCodeLayers = true;
        
        /**
         * constructor.
         * 
         * @since 1.0
         */
        public Builder() { isCommitted = false; }

        /**
         * sets the parent class loader, default is the context class loader
         * of the current thread.
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setParent(final ClassLoader parent) {
            check();
            this.parent = parent;
            return this;
        }
        
        /**
         * sets the load mode for the (static) code layers, default is "current first".
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setLoadMode(final LoadMode loadMode) {
            check();
            this.loadMode = loadMode;
            return this;
        }
        
        /**
         * sets whether to use the a top code cache or not,
         * default is true.
         * <p>
         * Note that the default is the opposite in the {@link LayeredClassLoader}.
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setWithTopCodeCache(final boolean isWithTopCodeCache) {
            check();
            this.isWithTopCodeCache = isWithTopCodeCache;
            return this;
        }

        /**
         * sets the top code cache factory,
         * default is a new instance of {@link DefaultTopCodeCacheFactory} with default settings.
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setTopCodeCacheFactory(final TopCodeCacheFactory topCodeCacheFactory) {
            check();
            this.topCodeCacheFactory = topCodeCacheFactory;
            return this;
        }

        /**
         * sets the load mode for the top code cache, default is "parent first".
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setTopLoadMode(final LoadMode topLoadMode) {
            check();
            this.topLoadMode = topLoadMode;
            return this;
        }
        
        /**
         * sets whether to allow the same class names in multiple code layers, default is true.
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setAllowSameClassNamesInMultipleCodeLayers(
                final boolean allowSameClassNamesInMultipleCodeLayers) {
            check();
            this.allowSameClassNamesInMultipleCodeLayers = allowSameClassNamesInMultipleCodeLayers;
            return this;
        }
        
        /**
         * sets whether to allow the same class names in code layers and parent class loader, default is true.
         * 
         * @return this, for chaining calls
         * @throws IllegalStateException if the builder had already been used to build an instance
         * 
         * @since 1.0
         */
        public Builder setAllowSameClassNamesInParentAndCodeLayers(
                final boolean allowSameClassNamesInParentAndCodeLayers) {
            check();
            this.allowSameClassNamesInParentAndCodeLayers = allowSameClassNamesInParentAndCodeLayers;
            return this;
        }

        /**
         * gets the parent class loader.
         * 
         * @since 1.0
         */
        public ClassLoader getParent() {
            return parent;
        }
        
        /**
         * gets the load mode for the (static) code layers.
         * 
         * @since 1.0
         */
        public LoadMode getLoadMode() {
            return loadMode;
        }
        
        /**
         * gets whether to use the a top code cache or not.
         * 
         * @since 1.0
         */
        public boolean isWithTopCodeCache() {
            return isWithTopCodeCache;
        }
        
        /**
         * gets the load mode for the top code cache.
         * 
         * @since 1.0
         */
        public LoadMode getTopLoadMode() {
            return topLoadMode;
        }
        
        /**
         * gets the top code cache factory.
         * 
         * @since 1.0
         */
        public TopCodeCacheFactory getTopCodeCacheFactory() {
            return topCodeCacheFactory;
        }
        
        /**
         * gets whether to allow the same class names in multiple code layers.
         * 
         * @since 1.0
         */
        public boolean isAllowSameClassNamesInMultipleCodeLayers() {
            return allowSameClassNamesInMultipleCodeLayers;
        }
        /**
         * gets whether to allow the same class names in code layers and parent class loader.
         * 
         * @since 1.0
         */
        public boolean isAllowSameClassNamesInParentAndCodeLayers() {
            return allowSameClassNamesInParentAndCodeLayers;
        }
        
        private Builder commit() {
            if (!isCommitted) {
                if (parent == null) {
                    parent = Thread.currentThread().getContextClassLoader();
                }
                if (loadMode == null) {
                    loadMode = LoadMode.CURRENT_FIRST;
                }
                if (topLoadMode == null) {
                    topLoadMode = LoadMode.PARENT_FIRST;
                }
                if (topCodeCacheFactory == null) {
                    topCodeCacheFactory = new DefaultTopCodeCacheFactory.Builder().build();
                }
                isCommitted = true;
            }
            return this;
        }
        
        /**
         * builds a new instance of {@link LayeredEngine}.
         * 
         * @since 1.0
         */
        public LayeredEngine build() {
            commit();
            return new LayeredEngine(this);
        }
                
        private void check() {
            if (isCommitted) {
                throw new IllegalStateException("Builder already used.");
            }
        }

    }

}

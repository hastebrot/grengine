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

package ch.grengine.code;

import java.util.Date;
import java.util.Set;

import ch.grengine.source.Source;


/**
 * Information about compiled source.
 * <p>
 * Contains the originating {@link Source} instance, the main class name
 * and the last modified at compile time.
 * 
 * @since 1.0
 * 
 * @author Alain Stalder
 * @author Made in Switzerland.
 */
public class CompiledSourceInfo {
    
    private final Source source;
    private final String mainClassName;
    private final Set<String> classNames;
    private final long lastModifiedAtCompileTime;
    
    /**
     * constructor.
     * 
     * @param source the originating {@link Source} instance
     * @param mainClassName the main class name
     * @param lastModifiedAtCompileTime the last modified at compile time
     * @throws IllegalArgumentException if source or main class name are null
     * 
     * @since 1.0
     */
    public CompiledSourceInfo(final Source source, final String mainClassName, 
            final Set<String> classNames, final long lastModifiedAtCompileTime) {
        if (source == null) {
            throw new IllegalArgumentException("Source is null.");
        }
        if (mainClassName == null) {
            throw new IllegalArgumentException("Main class name is null.");
        }
        if (classNames == null) {
            throw new IllegalArgumentException("Class names are null.");
        }
        this.source = source;
        this.mainClassName = mainClassName;
        this.classNames = classNames;
        this.lastModifiedAtCompileTime = lastModifiedAtCompileTime;
    }

    /**
     * gets the originating {@link Source} instance.
     * 
     * @since 1.0
     */
    public Source getSource() {
        return source;
    }

    /**
     * gets the main class name.
     * 
     * @since 1.0
     */
    public String getMainClassName() {
        return mainClassName;
    }

    /**
     * gets all class names.
     * 
     * @since 1.0
     */
    public Set<String> getClassNames() {
        return classNames;
    }

    /**
     * gets the last modified at compile time.
     * 
     * @since 1.0
     */
    public long getLastModifiedAtCompileTime() {
        return lastModifiedAtCompileTime;
    }

    /**
     * returns a string suitable for logging.
     * 
     * @since 1.0
     */
    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "[source=" + source + ", mainClassName=" + mainClassName +
                ", classNames=" + classNames + ", lastModifiedAtCompileTime=" +
                new Date(lastModifiedAtCompileTime) + "]";
    }

}

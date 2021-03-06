/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class PatternCache {

    private static final LoadingCache<String, CachedPattern> CACHE = Caffeine.newBuilder()
            .build(s -> {
                try {
                    return new CachedPattern(Pattern.compile(s));
                } catch (PatternSyntaxException e) {
                    return new CachedPattern(e);
                }
            });

    public static Pattern compile(String regex) {
        CachedPattern pattern = CACHE.get(regex);
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.ex != null) {
            throw pattern.ex;
        } else {
            return pattern.instance;
        }
    }

    /**
     * Compiles delimiter pattern with the given escape sequence.
     *
     * @param delimiter the delimiter (the thing separating components)
     * @param escape the string used to escape the delimiter where the pattern shouldn't match
     * @return a pattern
     */
    public static Pattern compileDelimiterPattern(String delimiter, String escape) {
        String pattern = "(?<!" + Pattern.quote(escape) + ")" + Pattern.quote(delimiter);
        return compile(pattern);
    }

    private static final class CachedPattern {
        private final Pattern instance;
        private final PatternSyntaxException ex;

        public CachedPattern(Pattern instance) {
            this.instance = instance;
            this.ex = null;
        }

        public CachedPattern(PatternSyntaxException ex) {
            this.instance = null;
            this.ex = ex;
        }
    }

    private PatternCache() {}

}

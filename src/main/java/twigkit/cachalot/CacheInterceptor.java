/*
 * Copyright 2010 Hjortur Stefan Olafsson
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
 *
 */
package twigkit.cachalot;

import com.google.inject.Inject;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Intercepts calls to methods that are annotated with {@link Cache} and returns
 * the value that corresponds to the method's parameters without invoking it.
 * <p/>
 * If the cache does not have a value that corresponds to the method parameters
 * then the method is invoked, and the return value cached before returning it
 * to the caller.
 *
 * @author mr.olafsson
 */
public class CacheInterceptor implements MethodInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(CacheInterceptor.class);
	private CacheManager cacheManager;

	public CacheInterceptor() {
	}

	/**
	 * Instantiate the CacheInterceptor with a {@link net.sf.ehcache.CacheManager}
	 * instance.
	 *
	 * @param cacheManager
	 */
	public CacheInterceptor(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	/**
	 * @param invocation
	 * @return
	 * @throws Throwable
	 */
	public Object invoke(MethodInvocation invocation) throws Throwable {
		long start = 0;

		if (logger.isDebugEnabled()) {
			start = System.currentTimeMillis();
		}

		/*
		 * Get a suitable key based on the method's arguments
		 */
		Object key = getKey(invocation.getArguments());

		/*
		 * Get the Cached annotation
		 */
		twigkit.cachalot.Cache conf = (twigkit.cachalot.Cache) invocation.getMethod().getAnnotation(twigkit.cachalot.Cache.class);

		/*
		 * Get a cache that corresponds to the annotation elements
		 */
		Cache cache = getCache(conf);

		/*
		 * If a cache is found, then check the cache for a value that corresponds
		 * to the method's argument list
		 */
		if (cache != null) {
			Element cacheElement = cache.get(key);

			if (cacheElement != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Call to [" + invocation.getMethod().getName() + "] returns cached value for key [" + key + "]");
				}
				// Handling non-serializable objects if possible
				if (cacheElement.isSerializable()) {
					Object v = cacheElement.getValue();
					if (v != null) {
						if (logger.isDebugEnabled()) {
							logger.debug("Call to [" + invocation.getMethod().getName() + "] returned cached value in " + (System.currentTimeMillis() - start) + " ms.");
						}
						return v;
					}
				} else if (!conf.diskPersistent()) {
					Object v = cacheElement.getObjectValue();
					if (v != null) {
						if (logger.isDebugEnabled()) {
							logger.debug("Call to [" + invocation.getMethod().getName() + "] returned cached value in " + (System.currentTimeMillis() - start) + " ms.");
						}
						return v;
					}
				}

			}
		}

		/*
		 * If no cached value is found, invoke the method
		 */
		Object returnValue = invocation.proceed();
		if (logger.isDebugEnabled()) {
			logger.debug("Invoked [" + invocation.getMethod().getName() + "]");
		}

		/*
		 * If a cache was found, then add the return value to it with the hashcode
		 * of the method's arguments as a key
		 */
		if (cache != null && returnValue != null) {
			Element e = new Element(key, returnValue);

			if (e.isSerializable() || !conf.diskPersistent()) {
				if (logger.isDebugEnabled()) {
					logger.debug("Caching return value [" + key + " > " + returnValue + "]");
				}
				cache.put(new Element(key, returnValue));
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("Return value could not be cached - not serializable and attempting to persist to disk");
				}
			}
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Call to [" + invocation.getMethod().getName() + "] returned invoked value in " + (System.currentTimeMillis() - start) + " ms.");
		}
		return returnValue;
	}

	/**
	 * Get a Cache instance based on the {@link Cache} annotion parameters.
	 *
	 * @param conf The Cached annotation used for the target method
	 * @return A Cache instance to lookup the return value
	 */
	private Cache getCache(twigkit.cachalot.Cache conf) {
		/*
		 * Look for a cache based on the annotations's name attribute
		 */
		Cache cache = cacheManager.getCache(conf.name());

		/*
		 * If no cache is found, then create a memory one based on the annotation
		 * elements
		 */
		if (cache == null) {
			cache = new Cache(conf.name(), conf.maxElementsInMemory(), conf.overflowToDisk(), conf.eternal(), conf.timeToLiveSeconds(), conf.timeToIdleSeconds(), conf.diskPersistent(), conf.diskExpiryThreadIntervalSeconds());
			cacheManager.addCache(cache);
		}

		return cache;
	}

	/**
	 * Create a suitable cache key based on the target method's parameters.
	 *
	 * @param arguments Arguments passed to the method being cached
	 * @return A suitable lookup key for the cache
	 */
	private Object getKey(Object[] arguments) {
		if (arguments.length == 1 && arguments[0] instanceof String) {
			return arguments[0];
		} else {
			return Arrays.deepHashCode(arguments);
		}
	}

	@Inject
	public void setCacheManager(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}
}

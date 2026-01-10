package com.ustadmobile.meshrabiya.service.ml

import android.util.Log
import com.google.ai.edge.litert.CompiledModel

object LitertCompiledModelFactory {
    private const val TAG = "LitertCompiledModelFactory"

    /**
     * Try to create a CompiledModel from raw model bytes and options using reflection.
     * This avoids referencing private APIs directly and fails gracefully when the
     * runtime LiteRT artifact does not expose a public factory.
     */
    fun tryCreateFromBytes(modelBytes: ByteArray, options: CompiledModel.Options): CompiledModel? {
        // 1) Try to find a public static create(byte[], Options) method on CompiledModel
        try {
            val cmClass = CompiledModel::class.java

            // Search for a static/public create method that accepts (byte[], CompiledModel.Options)
            val methods = cmClass.methods
            for (m in methods) {
                if (m.name == "create") {
                    val params = m.parameterTypes
                    try {
                        if (params.size >= 1 && params[0] == ByteArray::class.java) {
                            // Build argument array compatible with the method
                            val args = mutableListOf<Any?>()
                            args.add(modelBytes)
                            if (params.size >= 2) {
                                // Pass options if compatible
                                if (params[1].isAssignableFrom(CompiledModel.Options::class.java)) {
                                    args.add(options)
                                } else {
                                    // second param not options - skip
                                    continue
                                }
                            }

                            m.isAccessible = true
                            val result = m.invoke(null, *args.toTypedArray())
                            if (result is CompiledModel) return result
                        }
                    } catch (iae: IllegalArgumentException) {
                        // Ignore and continue searching
                    }
                }
            }

            // 2) Try to locate a companion/builder-style create that requires a Model wrapper
            //    and attempt to construct a Model instance reflectively from bytes.
            try {
                val companionField = cmClass.getDeclaredField("Companion")
                companionField.isAccessible = true
                val companion = companionField.get(null)
                val companionClass = companion::class.java
                val compMethods = companionClass.methods
                for (m in compMethods) {
                    if (m.name == "create") {
                        val params = m.parameterTypes
                        // If first param is not byte[] try to find a Model-like factory
                        if (params.isNotEmpty() && params[0] == ByteArray::class.java) {
                            try {
                                m.isAccessible = true
                                val args = arrayOf<Any?>(modelBytes, options)
                                val result = m.invoke(companion, *args)
                                if (result is CompiledModel) return result
                            } catch (e: Exception) {
                                // continue
                            }
                        } else if (params.isNotEmpty()) {
                            // Parameter is some Model class - attempt to create model container
                            val modelParamClass = params[0]
                            try {
                                // Try common factory names or constructors on the modelParamClass
                                val modelObj = tryCreateModelContainer(modelParamClass, modelBytes)
                                if (modelObj != null) {
                                    val args = mutableListOf<Any?>()
                                    args.add(modelObj)
                                    // Fill remaining params with reasonable defaults
                                    for (i in 1 until params.size) {
                                        args.add(defaultValueFor(params[i]))
                                    }
                                    m.isAccessible = true
                                    val result = m.invoke(companion, *args.toTypedArray())
                                    if (result is CompiledModel) return result
                                }
                            } catch (e: Exception) {
                                // ignore and continue
                            }
                        }
                    }
                }
            } catch (nsfe: NoSuchFieldException) {
                // Companion not present - ignore
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reflection path failed to create CompiledModel: ${e.message}", e)
        }

        Log.w(TAG, "No compatible CompiledModel factory found in LiteRT runtime; LiteRT will be disabled for this model.")
        return null
    }

    private fun defaultValueFor(clazz: Class<*>): Any? {
        return when {
            clazz == Boolean::class.javaPrimitiveType || clazz == java.lang.Boolean::class.java -> false
            clazz == Int::class.javaPrimitiveType || clazz == java.lang.Integer::class.java -> 0
            clazz == Long::class.javaPrimitiveType || clazz == java.lang.Long::class.java -> 0L
            else -> null
        }
    }

    private fun tryCreateModelContainer(modelClass: Class<*>, bytes: ByteArray): Any? {
        try {
            // Try common static factory names
            val factoryNames = listOf("create", "fromByteArray", "fromFlatBuffer", "parseFrom")
            for (name in factoryNames) {
                try {
                    val m = modelClass.getMethod(name, ByteArray::class.java)
                    m.isAccessible = true
                    return m.invoke(null, bytes)
                } catch (nsme: NoSuchMethodException) {
                    // continue
                }
            }

            // Try constructor(ByteArray)
            try {
                val ctor = modelClass.getConstructor(ByteArray::class.java)
                ctor.isAccessible = true
                return ctor.newInstance(bytes)
            } catch (nsc: NoSuchMethodException) {
                // give up
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create model container reflectively: ${e.message}", e)
        }
        return null
    }
}

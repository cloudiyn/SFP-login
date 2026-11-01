/*
 * This file is part of SFP-login, licensed under the GNU Affero General Public License v3.0 or later.
 *
 * Copyright (C) ksqeib (庆灵) <ksqeib@qq.com>
 * Copyright (C) contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package cn.starfallplain.sfplogin.reflect

import io.netty.buffer.ByteBuf
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.lang.reflect.Method

/**
 * 基于 ASM 字节码生成的反射加速工具。
 *
 * 通过在运行时生成桥接类来消除 [Method.invoke] / [java.lang.reflect.Field.get] 的反射开销，
 * 适用于调用频率较高的内部 API（如包体热路径中的 ProtocolUtils.readVarInt）。
 *
 * 生成的类加载在一个独立的子 [ClassLoader] 中，与插件类加载器相互隔离。
 */
object AsmReflectUtil {

    /** 独立类加载器，用于加载所有 ASM 生成的类。 */
    private val generatedClassLoader = object : ClassLoader(AsmReflectUtil::class.java.classLoader) {
        fun defineClass(name: String, bytes: ByteArray): Class<*> =
            super.defineClass(name, bytes, 0, bytes.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 为静态方法 [staticMethod] 生成一个 [IntFromByteBufInvoker] 实现。
     *
     * 生成的类直接通过 INVOKESTATIC 字节码调用目标方法，没有反射调用开销。
     * 用于 `ProtocolUtils.readVarInt(ByteBuf)` 等热路径静态工具方法。
     *
     * @param staticMethod 必须是签名为 `(ByteBuf): Int` 的静态方法
     */
    fun makeIntFromByteBufInvoker(staticMethod: Method): IntFromByteBufInvoker {
        require(staticMethod.parameterCount == 1) { "Method must take exactly 1 parameter" }
        require(staticMethod.returnType == Int::class.javaPrimitiveType) { "Method must return int" }

        val ownerInternalName = Type.getInternalName(staticMethod.declaringClass)
        val methodDescriptor = Type.getMethodDescriptor(staticMethod)
        val generatedName = "cn/starfallplain/sfplogin/reflect/generated/IntFromByteBuf_${
            staticMethod.declaringClass.simpleName}_${staticMethod.name}"
        val generatedBinaryName = generatedName.replace('/', '.')

        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            V11,
            ACC_PUBLIC or ACC_FINAL or ACC_SUPER,
            generatedName,
            null,
            "java/lang/Object",
            arrayOf(Type.getInternalName(IntFromByteBufInvoker::class.java))
        )

        // Constructor
        cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        // invoke(ByteBuf): Int
        val invokerDescriptor = "(Lio/netty/buffer/ByteBuf;)I"
        cw.visitMethod(ACC_PUBLIC or ACC_FINAL, "invoke", invokerDescriptor, null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 1)
            // Cast to the actual parameter type if necessary
            val paramType = staticMethod.parameterTypes[0]
            if (paramType != ByteBuf::class.java) {
                visitTypeInsn(CHECKCAST, Type.getInternalName(paramType))
            }
            visitMethodInsn(INVOKESTATIC, ownerInternalName, staticMethod.name, methodDescriptor, false)
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        cw.visitEnd()

        val bytes = cw.toByteArray()
        val generatedClass = generatedClassLoader.defineClass(generatedBinaryName, bytes)
        @Suppress("UNCHECKED_CAST")
        return generatedClass.getDeclaredConstructor().newInstance() as IntFromByteBufInvoker
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Generated interfaces
    // ──────────────────────────────────────────────────────────────────────────

    /** 生成的桥接接口：从 [ByteBuf] 读取一个 int（用于 ProtocolUtils.readVarInt）。 */
    fun interface IntFromByteBufInvoker {
        fun invoke(buf: ByteBuf): Int
    }
}


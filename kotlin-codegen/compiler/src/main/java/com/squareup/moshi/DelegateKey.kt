/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.moshi

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asTypeName
import javax.lang.model.element.AnnotationMirror

/** A JsonAdapter that can be used to encode and decode a particular field. */
internal data class DelegateKey(
  private val type: TypeName,
  private val jsonQualifiers: Set<AnnotationMirror>
) {
  val nullable get() = type.nullable || type is TypeVariableName

  fun reserveName(nameAllocator: NameAllocator) {
    val qualifierNames = jsonQualifiers.joinToString("") {
      "At${it.annotationType.asElement().simpleName}"
    }
    nameAllocator.newName("${type.toVariableName().decapitalize()}${qualifierNames}Adapter", this)
  }

  /** Returns an adapter to use when encoding and decoding this property. */
  fun generateProperty(nameAllocator: NameAllocator, enclosing: AdapterGenerator): PropertySpec {
    val adapterTypeName = ParameterizedTypeName.get(
        JsonAdapter::class.asTypeName(), type)
    val qualifiers = jsonQualifiers
    val standardArgs = arrayOf(enclosing.moshiParam,
        if (type is ClassName && qualifiers.isEmpty()) {
          ""
        } else {
          CodeBlock.of("<%T>", type)
        },
        type.makeType(enclosing.elements, enclosing.typesParam, enclosing.genericTypeNames))
    val standardArgsSize = standardArgs.size + 1
    val (initializerString, args) = when {
      qualifiers.isEmpty() -> "" to emptyArray()
      qualifiers.size == 1 -> {
        ", %${standardArgsSize}T::class.java" to arrayOf(
            qualifiers.first().annotationType.asTypeName())
      }
      else -> {
        val initString = qualifiers
            .mapIndexed { index, _ ->
              val annoClassIndex = standardArgsSize + index
              return@mapIndexed "%${annoClassIndex}T::class.java"
            }
            .joinToString()
        val initArgs = qualifiers
            .map { it.annotationType.asTypeName() }
            .toTypedArray()
        ", $initString" to initArgs
      }
    }
    val finalArgs = arrayOf(*standardArgs, *args)

    val nullModifier = if (nullable) ".nullSafe()" else ".nonNull()"

    return PropertySpec.builder(nameAllocator.get(this), adapterTypeName, KModifier.PRIVATE)
        .initializer("%1N.adapter%2L(%3L$initializerString)$nullModifier", *finalArgs)
        .build()
  }
}

/**
 * Returns a suggested variable name derived from a list of type names. This just concatenates,
 * yielding types like MapOfStringLong.
 */
private fun List<TypeName>.toVariableNames(): String {
  return joinToString("") { it.toVariableName() }
}

/** Returns a suggested variable name derived from a type name, like nullableListOfString. */
private fun TypeName.toVariableName(): String {
  val base = when (this) {
    is ClassName -> simpleName()
    is ParameterizedTypeName -> rawType.simpleName() + "Of" + typeArguments.toVariableNames()
    is WildcardTypeName -> (lowerBounds + upperBounds).toVariableNames()
    is TypeVariableName -> name + bounds.toVariableNames()
    else -> throw IllegalArgumentException("Unrecognized type! $this")
  }

  return if (nullable) {
    "Nullable$base"
  } else {
    base
  }
}

/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.jvm.lower.serialization.ir

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.IrModuleSerializer

class JvmIrModuleSerializer(
    logger: LoggingContext,
    declarationTable: DeclarationTable,
    bodiesOnlyForInlines: Boolean = true
) : IrModuleSerializer(logger, declarationTable, JvmMangler, bodiesOnlyForInlines)
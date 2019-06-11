/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.checkers

import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.diagnostics.reportDiagnosticOnce
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifier
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.isCompanionObject
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyPublicApi

class ApiModeDeclarationChecker : DeclarationChecker {
    override fun check(declaration: KtDeclaration, descriptor: DeclarationDescriptor, context: DeclarationCheckerContext) {
        val isApi = (descriptor as? DeclarationDescriptorWithVisibility)?.isEffectivelyPublicApi ?: return
        if (!isApi) return
        val modifier = declaration.visibilityModifier()?.node?.elementType as? KtModifierKeywordToken
        if (modifier != null) return
        if (excludeForDiagnostic(descriptor)) return
        context.trace.reportDiagnosticOnce(Errors.NO_EXPLICIT_VISIBILITY_IN_API_MODE.on(declaration))
    }

    /**
     * Exclusion list:
     * 1. Primary constructors of public API classes
     * 2. Properties of data classes in public API
     * 3. Members of public API interfaces
     * 4. do not report overrides of public API? effectively, this means 'no report on overrides at all'
     * 5. Companion objects of public API classes
     *
     * Do we need something like @PublicApiFile to disable (or invert) this inspection per-file?
     */
    private fun excludeForDiagnostic(descriptor: DeclarationDescriptor): Boolean {
        /* 1. */ if ((descriptor as? ClassConstructorDescriptor)?.isPrimary == true) return true
        /* 2. */ if (descriptor is PropertyDescriptor && (descriptor.containingDeclaration as? ClassDescriptor)?.isData == true) return true
        val isMemberOfPublicInterface =
            (descriptor.containingDeclaration as? ClassDescriptor)?.let { DescriptorUtils.isInterface(it) && it.effectiveVisibility().publicApi }
        /* 3. */ if (descriptor is CallableDescriptor && isMemberOfPublicInterface == true) return true
        /* 4. */ if ((descriptor as? CallableDescriptor)?.overriddenDescriptors?.isNotEmpty() == true) return true
        /* 5. */ if (descriptor.isCompanionObject()) return true
        return false
    }

    companion object {
        fun isEnabled(settings: LanguageVersionSettings): Boolean {
            return settings.getFlag(AnalysisFlags.apiMode)
        }
    }
}
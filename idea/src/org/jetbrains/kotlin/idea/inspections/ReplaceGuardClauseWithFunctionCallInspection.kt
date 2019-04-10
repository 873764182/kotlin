/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license 
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.intentions.callExpression
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class ReplaceGuardClauseWithFunctionCallInspection : AbstractApplicabilityBasedInspection<KtIfExpression>(
    KtIfExpression::class.java
) {
    companion object {
        private const val ILLEGAL_STATE_EXCEPTION = "IllegalStateException"
        private const val ILLEGAL_ARGUMENT_EXCEPTION = "IllegalArgumentException"
    }

    private enum class KotlinFunction(val functionName: String) {
        CHECK("check"), CHECK_NOT_NULL("checkNotNull"), REQUIRE("require"), REQUIRE_NOT_NULL("requireNotNull");

        val fqName: String
            get() = "kotlin.$functionName"
    }

    override fun inspectionText(element: KtIfExpression) = "Replace guard clause with kotlin's function call"

    override val defaultFixText = "Replace with kotlin's function call"

    override fun fixText(element: KtIfExpression) =
        element.getKotlinFunction()?.let { "Replace with '${it.functionName}()' call" } ?: defaultFixText

    override fun isApplicable(element: KtIfExpression): Boolean {
        if (element.condition == null) return false
        val call = element.getCallExpression() ?: return false
        val calleeText = call.calleeExpression?.text ?: return false
        val valueArguments = call.valueArguments
        if (valueArguments.size > 1) return false
        if (calleeText != ILLEGAL_STATE_EXCEPTION && calleeText != ILLEGAL_ARGUMENT_EXCEPTION) return false
        val fqName = call.resolveToCall()?.resultingDescriptor?.fqNameSafe?.parent()
        return fqName == FqName("kotlin.$calleeText") || fqName == FqName("java.lang.$calleeText")
    }

    override fun applyTo(element: PsiElement, project: Project, editor: Editor?) {
        val ifExpression = element as? KtIfExpression ?: return
        val condition = ifExpression.condition ?: return
        val call = ifExpression.getCallExpression() ?: return
        val argument = call.valueArguments.firstOrNull()?.getArgumentExpression()
        val commentSaver = CommentSaver(ifExpression)
        val psiFactory = KtPsiFactory(ifExpression)
        val replaced = when (val kotlinFunction = ifExpression.getKotlinFunction(call)) {
            KotlinFunction.CHECK, KotlinFunction.REQUIRE -> {
                val (excl, newCondition) = if (condition is KtPrefixExpression && condition.operationToken == KtTokens.EXCL) {
                    "" to (condition.baseExpression ?: return)
                } else {
                    "!" to condition
                }
                val newExpression = if (argument == null) {
                    psiFactory.createExpressionByPattern("${kotlinFunction.fqName}($excl$0)", newCondition)
                } else {
                    psiFactory.createExpressionByPattern("${kotlinFunction.fqName}($excl$0) { $1 }", newCondition, argument)
                }
                val replaced = ifExpression.replaced(newExpression)
                val newCall = (replaced as? KtDotQualifiedExpression)?.callExpression
                val negatedExpression = newCall?.valueArguments?.firstOrNull()?.getArgumentExpression() as? KtPrefixExpression
                if (negatedExpression != null) {
                    SimplifyNegatedBinaryExpressionInspection.simplifyNegatedBinaryExpressionIfNeeded(negatedExpression)
                }
                replaced
            }
            KotlinFunction.CHECK_NOT_NULL, KotlinFunction.REQUIRE_NOT_NULL -> {
                val nullCheckedExpression = condition.notNullCheckExpression() ?: return
                val newExpression = if (argument == null) {
                    psiFactory.createExpressionByPattern("${kotlinFunction.fqName}($0)", nullCheckedExpression)
                } else {
                    psiFactory.createExpressionByPattern("${kotlinFunction.fqName}($0) { $1 }", nullCheckedExpression, argument)
                }
                ifExpression.replaced(newExpression)
            }
            else -> return
        }
        commentSaver.restore(replaced)
        ShortenReferences.DEFAULT.process(replaced)
    }

    private fun KtIfExpression.getCallExpression(): KtCallExpression? {
        val throwExpression = this.then?.let {
            it as? KtThrowExpression ?: (it as? KtBlockExpression)?.statements?.singleOrNull() as? KtThrowExpression
        } ?: return null
        return throwExpression.thrownExpression?.let {
            it as? KtCallExpression ?: (it as? KtQualifiedExpression)?.callExpression
        }
    }

    private fun KtIfExpression.getKotlinFunction(call: KtCallExpression? = getCallExpression()): KotlinFunction? {
        val calleeText = call?.calleeExpression?.text ?: return null
        val isNotNullCheck = condition.notNullCheckExpression() != null
        return when (calleeText) {
            ILLEGAL_STATE_EXCEPTION -> if (isNotNullCheck) KotlinFunction.CHECK_NOT_NULL else KotlinFunction.CHECK
            ILLEGAL_ARGUMENT_EXCEPTION -> if (isNotNullCheck) KotlinFunction.REQUIRE_NOT_NULL else KotlinFunction.REQUIRE
            else -> null
        }
    }

    private fun KtExpression?.notNullCheckExpression(): KtExpression? {
        if (this == null) return null
        if (this !is KtBinaryExpression) return null
        if (this.operationToken != KtTokens.EQEQ) return null
        val left = this.left ?: return null
        val right = this.right ?: return null
        return when {
            right.isNullConstant() -> left
            left.isNullConstant() -> right
            else -> null
        }
    }

    private fun KtExpression.isNullConstant(): Boolean {
        return (this as? KtConstantExpression)?.text == KtTokens.NULL_KEYWORD.value
    }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators.error

import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.customize.NamedSectionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.customize.Section

/** Error customization sections */
sealed class ErrorSection(name: String) : Section(name) {
    /** Use this section to add additional trait implementations to the generated operation errors */
    data class OperationErrorAdditionalTraitImpls(val errorType: RuntimeType) : ErrorSection("OperationErrorAdditionalTraitImpls")
}

/** Customizations for generated errors */
abstract class ErrorCustomization : NamedSectionGenerator<ErrorSection>()
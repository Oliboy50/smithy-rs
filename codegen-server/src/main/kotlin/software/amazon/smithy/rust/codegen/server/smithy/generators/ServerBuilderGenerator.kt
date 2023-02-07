/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute.Companion.derive
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.conditionalBlock
import software.amazon.smithy.rust.codegen.core.rustlang.deprecatedShape
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.implInto
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.expectRustMetadata
import software.amazon.smithy.rust.codegen.core.smithy.isOptional
import software.amazon.smithy.rust.codegen.core.smithy.isRustBoxed
import software.amazon.smithy.rust.codegen.core.smithy.makeMaybeConstrained
import software.amazon.smithy.rust.codegen.core.smithy.makeOptional
import software.amazon.smithy.rust.codegen.core.smithy.makeRustBoxed
import software.amazon.smithy.rust.codegen.core.smithy.mapRustType
import software.amazon.smithy.rust.codegen.core.smithy.module
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.smithy.traits.SyntheticInputTrait
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.letIf
import software.amazon.smithy.rust.codegen.core.util.redactIfNecessary
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.ServerRuntimeType
import software.amazon.smithy.rust.codegen.server.smithy.canReachConstrainedShape
import software.amazon.smithy.rust.codegen.server.smithy.hasConstraintTraitOrTargetHasConstraintTrait
import software.amazon.smithy.rust.codegen.server.smithy.targetCanReachConstrainedShape
import software.amazon.smithy.rust.codegen.server.smithy.traits.isReachableFromOperationInput
import software.amazon.smithy.rust.codegen.server.smithy.withComposableInlineModule
import software.amazon.smithy.rust.codegen.server.smithy.wouldHaveConstrainedWrapperTupleTypeWerePublicConstrainedTypesEnabled

/**
 * Generates a builder for the Rust type associated with the [StructureShape].
 *
 * This generator is meant for use by the server project. Clients use the [BuilderGenerator] from the `codegen-client`
 * Gradle subproject instead.
 *
 * This builder is different in that it enforces [constraint traits] upon calling `.build()`. If any constraint
 * violations occur, the `build` method returns them.
 *
 * These are the main differences with the builders generated by the client's [BuilderGenerator]:
 *
 * - The design of this builder is simpler and closely follows what you get when using the [derive_builder] crate:
 *     * The builder has one method per struct member named _exactly_ like the struct member and whose input type
 *       matches _exactly_ the struct's member type. This method is generated by [renderBuilderMemberFn].
 *     * The builder has one _setter_ method (i.e. prefixed with `set_`) per struct member whose input type is the
 *       corresponding _unconstrained type_ for the member. This method is always `pub(crate)` and meant for use for
 *       server deserializers only.
 *     * There are no convenience methods to add items to vector and hash map struct members.
 * - The builder is not `PartialEq`. This is because the builder's members may or may not have been constrained (their
 *   types hold `MaybeConstrained`), and so it doesn't make sense to compare e.g. two builders holding the same data
 *   values, but one builder holds the member in the constrained variant while the other one holds it in the unconstrained
 *   variant.
 * - The builder always implements `TryFrom<Builder> for Structure` or `From<Builder> for Structure`, depending on whether
 *   the structure is constrained (and hence enforcing the constraints might yield an error) or not, respectively.
 *
 * The builder is `pub(crate)` when `publicConstrainedTypes` is `false`, since in this case the user is never exposed
 * to constrained types, and only the server's deserializers need to enforce constraint traits upon receiving a request.
 * The user is exposed to [ServerBuilderGeneratorWithoutPublicConstrainedTypes] in this case instead, which intentionally
 * _does not_ enforce constraints.
 *
 * [constraint traits]: https://awslabs.github.io/smithy/2.0/spec/constraint-traits.html
 * [derive_builder]: https://docs.rs/derive_builder/latest/derive_builder/index.html
 */
class ServerBuilderGenerator(
    val codegenContext: ServerCodegenContext,
    private val shape: StructureShape,
) {
    companion object {
        /**
         * Returns whether a structure shape, whose builder has been generated with [ServerBuilderGenerator], requires a
         * fallible builder to be constructed.
         */
        fun hasFallibleBuilder(
            structureShape: StructureShape,
            model: Model,
            symbolProvider: SymbolProvider,
            takeInUnconstrainedTypes: Boolean,
        ): Boolean {
            val members = structureShape.members()
            fun isOptional(member: MemberShape) = symbolProvider.toSymbol(member).isOptional()
            fun hasDefault(member: MemberShape) = member.hasNonNullDefault()
            fun isNotConstrained(member: MemberShape) = !member.canReachConstrainedShape(model, symbolProvider)

            val notFallible = members.all {
                if (structureShape.isReachableFromOperationInput()) {
                    // When deserializing an input structure, constraints might not be satisfied by the data in the
                    // incoming request.
                    // For this builder not to be fallible, no members must be constrained (constraints in input must
                    // always be checked) and all members must _either_ be optional (no need to set it; not required)
                    // or have a default value.
                    isNotConstrained(it) && (isOptional(it) || hasDefault(it))
                } else {
                    // This structure will be constructed manually by the user.
                    // Constraints will have to be dealt with before members are set in the builder.
                    isOptional(it) || hasDefault(it)
                }
            }

            return if (takeInUnconstrainedTypes) {
                !notFallible && structureShape.canReachConstrainedShape(model, symbolProvider)
            } else {
                !notFallible
            }
        }
    }

    private val takeInUnconstrainedTypes = shape.isReachableFromOperationInput()
    private val model = codegenContext.model
    private val runtimeConfig = codegenContext.runtimeConfig
    private val publicConstrainedTypes = codegenContext.settings.codegenConfig.publicConstrainedTypes
    private val visibility = if (publicConstrainedTypes) Visibility.PUBLIC else Visibility.PUBCRATE
    private val symbolProvider = codegenContext.symbolProvider
    private val constrainedShapeSymbolProvider = codegenContext.constrainedShapeSymbolProvider
    private val pubCrateConstrainedShapeSymbolProvider = codegenContext.pubCrateConstrainedShapeSymbolProvider
    private val members: List<MemberShape> = shape.allMembers.values.toList()
    private val structureSymbol = symbolProvider.toSymbol(shape)
    private val builderSymbol = shape.serverBuilderSymbol(codegenContext)
    private val isBuilderFallible = hasFallibleBuilder(shape, model, symbolProvider, takeInUnconstrainedTypes)
    private val serverBuilderConstraintViolations =
        ServerBuilderConstraintViolations(codegenContext, shape, takeInUnconstrainedTypes)

    private val codegenScope = arrayOf(
        "RequestRejection" to ServerRuntimeType.requestRejection(runtimeConfig),
        "Structure" to structureSymbol,
        "From" to RuntimeType.From,
        "TryFrom" to RuntimeType.TryFrom,
        "MaybeConstrained" to RuntimeType.MaybeConstrained,
    )

    fun composeDocAndCodeWritable(rustCrate : RustCrate, writer: RustWriter) {
        val docWriter : () -> Unit = {
            writer.docs("See #D.", structureSymbol)
        }
        rustCrate.withComposableInlineModule(builderSymbol.module(), codegenContext, docWriter) {
            renderBuilder(this)
        }
    }

    fun render(writer: RustWriter) {
        writer.docs("See #D.", structureSymbol)
        writer.withInlineModule(builderSymbol.module()) {
            renderBuilder(this)
        }
    }

    private fun renderBuilder(writer: RustWriter) {
        if (isBuilderFallible) {
            serverBuilderConstraintViolations.render(
                writer,
                visibility,
                nonExhaustive = true,
                shouldRenderAsValidationExceptionFieldList = shape.isReachableFromOperationInput(),
            )

            // Only generate converter from `ConstraintViolation` into `RequestRejection` if the structure shape is
            // an operation input shape.
            if (shape.hasTrait<SyntheticInputTrait>()) {
                renderImplFromConstraintViolationForRequestRejection(writer)
            }

            if (takeInUnconstrainedTypes) {
                renderImplFromBuilderForMaybeConstrained(writer)
            }

            renderTryFromBuilderImpl(writer)
        } else {
            renderFromBuilderImpl(writer)
        }

        writer.docs("A builder for #D.", structureSymbol)
        // Matching derives to the main structure, - `PartialEq` (see class documentation for why), + `Default`
        // since we are a builder and everything is optional.
        val baseDerives = structureSymbol.expectRustMetadata().derives
        // Filter out any derive that isn't Debug or Clone. Then add a Default derive
        val builderDerives = baseDerives.filter { it == RuntimeType.Debug || it == RuntimeType.Clone } + RuntimeType.Default
        Attribute(derive(builderDerives)).render(writer)
        writer.rustBlock("${visibility.toRustQualifier()} struct Builder") {
            members.forEach { renderBuilderMember(this, it) }
        }

        writer.rustBlock("impl Builder") {
            for (member in members) {
                if (publicConstrainedTypes) {
                    renderBuilderMemberFn(this, member)
                }

                if (takeInUnconstrainedTypes) {
                    renderBuilderMemberSetterFn(this, member)
                }
            }
            renderBuildFn(this)
        }

        if (!structureSymbol.expectRustMetadata().hasDebugDerive()) {
            renderImplDebugForBuilder(writer)
        }
    }

    private fun renderImplFromConstraintViolationForRequestRejection(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl #{From}<ConstraintViolation> for #{RequestRejection} {
                fn from(constraint_violation: ConstraintViolation) -> Self {
                    let first_validation_exception_field = constraint_violation.as_validation_exception_field("".to_owned());
                    let validation_exception = crate::error::ValidationException {
                        message: format!("1 validation error detected. {}", &first_validation_exception_field.message),
                        field_list: Some(vec![first_validation_exception_field]),
                    };
                    Self::ConstraintViolation(
                        crate::operation_ser::serialize_structure_crate_error_validation_exception(&validation_exception)
                            .expect("impossible")
                    )
                }
            }
            """,
            *codegenScope,
        )
    }

    private fun renderImplFromBuilderForMaybeConstrained(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl #{From}<Builder> for #{StructureMaybeConstrained} {
                fn from(builder: Builder) -> Self {
                    Self::Unconstrained(builder)
                }
            }
            """,
            *codegenScope,
            "StructureMaybeConstrained" to structureSymbol.makeMaybeConstrained(),
        )
    }

    private fun renderBuildFn(implBlockWriter: RustWriter) {
        implBlockWriter.docs("""Consumes the builder and constructs a #D.""", structureSymbol)
        if (isBuilderFallible) {
            implBlockWriter.docs(
                """
                The builder fails to construct a #D if a [`ConstraintViolation`] occurs.
                """,
                structureSymbol,
            )

            if (serverBuilderConstraintViolations.all.size > 1) {
                implBlockWriter.docs("If the builder fails, it will return the _first_ encountered [`ConstraintViolation`].")
            }
        }
        implBlockWriter.rustTemplate(
            """
            pub fn build(self) -> #{ReturnType:W} {
                self.build_enforcing_all_constraints()
            }
            """,
            "ReturnType" to buildFnReturnType(isBuilderFallible, structureSymbol),
        )
        renderBuildEnforcingAllConstraintsFn(implBlockWriter)
    }

    private fun renderBuildEnforcingAllConstraintsFn(implBlockWriter: RustWriter) {
        implBlockWriter.rustBlockTemplate(
            "fn build_enforcing_all_constraints(self) -> #{ReturnType:W}",
            "ReturnType" to buildFnReturnType(isBuilderFallible, structureSymbol),
        ) {
            conditionalBlock("Ok(", ")", conditional = isBuilderFallible) {
                coreBuilder(this)
            }
        }
    }

    fun renderConvenienceMethod(implBlock: RustWriter) {
        implBlock.docs("Creates a new builder-style object to manufacture #D.", structureSymbol)
        implBlock.rustBlock("pub fn builder() -> #T", builderSymbol) {
            write("#T::default()", builderSymbol)
        }
    }

    private fun renderBuilderMember(writer: RustWriter, member: MemberShape) {
        val memberSymbol = builderMemberSymbol(member)
        val memberName = constrainedShapeSymbolProvider.toMemberName(member)
        // Builder members are crate-public to enable using them directly in serializers/deserializers.
        // During XML deserialization, `builder.<field>.take` is used to append to lists and maps.
        writer.write("pub(crate) $memberName: #T,", memberSymbol)
    }

    /**
     * Render a `foo` method to set shape member `foo`. The caller must provide a value with the exact same type
     * as the shape member's type.
     *
     * This method is meant for use by the user; it is not used by the generated crate's (de)serializers.
     *
     * This method is only generated when `publicConstrainedTypes` is `true`. Otherwise, the user has at their disposal
     * the method from [ServerBuilderGeneratorWithoutPublicConstrainedTypes].
     */
    private fun renderBuilderMemberFn(
        writer: RustWriter,
        member: MemberShape,
    ) {
        check(publicConstrainedTypes)
        val symbol = symbolProvider.toSymbol(member)
        val memberName = symbolProvider.toMemberName(member)

        val hasBox = symbol.mapRustType { it.stripOuter<RustType.Option>() }.isRustBoxed()
        val wrapInMaybeConstrained = takeInUnconstrainedTypes && member.targetCanReachConstrainedShape(model, symbolProvider)

        writer.documentShape(member, model)
        writer.deprecatedShape(member)

        if (hasBox && wrapInMaybeConstrained) {
            // In the case of recursive shapes, the member might be boxed. If so, and the member is also constrained, the
            // implementation of this function needs to immediately unbox the value to wrap it in `MaybeConstrained`,
            // and then re-box. Clippy warns us that we could have just taken in an unboxed value to avoid this round-trip
            // to the heap. However, that will make the builder take in a value whose type does not exactly match the
            // shape member's type.
            // We don't want to introduce API asymmetry just for this particular case, so we disable the lint.
            Attribute.AllowClippyBoxedLocal.render(writer)
        }
        writer.rustBlock("pub fn $memberName(mut self, input: ${symbol.rustType().render()}) -> Self") {
            withBlock("self.$memberName = ", "; self") {
                conditionalBlock("Some(", ")", conditional = !symbol.isOptional()) {
                    val maybeConstrainedVariant =
                        "${symbol.makeMaybeConstrained().rustType().namespace}::MaybeConstrained::Constrained"

                    var varExpr = if (symbol.isOptional()) "v" else "input"
                    if (hasBox) varExpr = "*$varExpr"
                    if (!constrainedTypeHoldsFinalType(member)) varExpr = "($varExpr).into()"

                    if (wrapInMaybeConstrained) {
                        conditionalBlock("input.map(##[allow(clippy::redundant_closure)] |v| ", ")", conditional = symbol.isOptional()) {
                            conditionalBlock("Box::new(", ")", conditional = hasBox) {
                                rust("$maybeConstrainedVariant($varExpr)")
                            }
                        }
                    } else {
                        write("input")
                    }
                }
            }
        }
    }

    /**
     * Returns whether the constrained builder member type (the type on which the `Constrained` trait is implemented)
     * is the final type the user sees when receiving the built struct. This is true when the corresponding constrained
     * type is public and not `pub(crate)`, which happens when the target is a structure shape, a union shape, or is
     * directly constrained.
     *
     * An example where this returns false is when the member shape targets a list whose members are lists of structures
     * having at least one `required` member. In this case the member shape is transitively but not directly constrained,
     * so the generated constrained type is `pub(crate)` and needs converting into the final type the user will be
     * exposed to.
     *
     * See [PubCrateConstrainedShapeSymbolProvider] too.
     */
    private fun constrainedTypeHoldsFinalType(member: MemberShape): Boolean {
        val targetShape = model.expectShape(member.target)
        return targetShape is StructureShape ||
            targetShape is UnionShape ||
            member.hasConstraintTraitOrTargetHasConstraintTrait(model, symbolProvider)
    }

    /**
     * Render a `set_foo` method.
     * This method is able to take in unconstrained types for constrained shapes, like builders of structs in the case
     * of structure shapes.
     *
     * This method is only used by deserializers at the moment and is therefore `pub(crate)`.
     */
    private fun renderBuilderMemberSetterFn(
        writer: RustWriter,
        member: MemberShape,
    ) {
        val builderMemberSymbol = builderMemberSymbol(member)
        val inputType = builderMemberSymbol.rustType().stripOuter<RustType.Option>().implInto()
            .letIf(
                // TODO(https://github.com/awslabs/smithy-rs/issues/1302, https://github.com/awslabs/smithy/issues/1179):
                //  The only reason why this condition can't simply be `member.isOptional`
                //  is because non-`required` blob streaming members are interpreted as
                //  `required`, so we can't use `member.isOptional` here.
                symbolProvider.toSymbol(member).isOptional(),
            ) { "Option<$it>" }
        val memberName = symbolProvider.toMemberName(member)

        writer.documentShape(member, model)
        // Setter names will never hit a reserved word and therefore never need escaping.
        writer.rustBlock("pub(crate) fn set_${member.memberName.toSnakeCase()}(mut self, input: $inputType) -> Self") {
            rust(
                """
                self.$memberName = ${
                // TODO(https://github.com/awslabs/smithy-rs/issues/1302, https://github.com/awslabs/smithy/issues/1179): See above.
                if (symbolProvider.toSymbol(member).isOptional()) {
                    "input.map(|v| v.into())"
                } else {
                    "Some(input.into())"
                }
                };
                self
                """,
            )
        }
    }

    private fun renderTryFromBuilderImpl(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl #{TryFrom}<Builder> for #{Structure} {
                type Error = ConstraintViolation;

                fn try_from(builder: Builder) -> Result<Self, Self::Error> {
                    builder.build()
                }
            }
            """,
            *codegenScope,
        )
    }

    private fun renderFromBuilderImpl(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl #{From}<Builder> for #{Structure} {
                fn from(builder: Builder) -> Self {
                    builder.build()
                }
            }
            """,
            *codegenScope,
        )
    }

    private fun renderImplDebugForBuilder(writer: RustWriter) {
        writer.rustBlock("impl #T for Builder", RuntimeType.Debug) {
            writer.rustBlock("fn fmt(&self, f: &mut #1T::Formatter<'_>) -> #1T::Result", RuntimeType.stdFmt) {
                rust("""let mut formatter = f.debug_struct("Builder");""")
                members.forEach { member ->
                    val memberName = symbolProvider.toMemberName(member)
                    val fieldValue = member.redactIfNecessary(model, "self.$memberName")

                    rust(
                        "formatter.field(${memberName.dq()}, &$fieldValue);",
                    )
                }
                rust("formatter.finish()")
            }
        }
    }

    /**
     * Returns the symbol for a builder's member.
     * All builder members are optional, but only some are `Option<T>`s where `T` needs to be constrained.
     */
    private fun builderMemberSymbol(member: MemberShape): Symbol =
        if (takeInUnconstrainedTypes && member.targetCanReachConstrainedShape(model, symbolProvider)) {
            val strippedOption = if (member.hasConstraintTraitOrTargetHasConstraintTrait(model, symbolProvider)) {
                constrainedShapeSymbolProvider.toSymbol(member)
            } else {
                pubCrateConstrainedShapeSymbolProvider.toSymbol(member)
            }
                // Strip the `Option` in case the member is not `required`.
                .mapRustType { it.stripOuter<RustType.Option>() }

            val hadBox = strippedOption.isRustBoxed()
            strippedOption
                // Strip the `Box` in case the member can reach itself recursively.
                .mapRustType { it.stripOuter<RustType.Box>() }
                // Wrap it in the Cow-like `constrained::MaybeConstrained` type, since we know the target member shape can
                // reach a constrained shape.
                .makeMaybeConstrained()
                // Box it in case the member can reach itself recursively.
                .letIf(hadBox) { it.makeRustBoxed() }
                // Ensure we always end up with an `Option`.
                .makeOptional()
        } else {
            constrainedShapeSymbolProvider.toSymbol(member).makeOptional()
        }

    /**
     * Writes the code to instantiate the struct the builder builds.
     *
     * Builder member types are either:
     *     1. `Option<MaybeConstrained<U>>`; or
     *     2. `Option<U>`.
     *
     * Where `U` is a constrained type.
     *
     * The structs they build have member types:
     *     a) `Option<T>`; or
     *     b) `T`.
     *
     * `U` is equal to `T` when:
     *     - the shape for `U` has a constraint trait and `publicConstrainedTypes` is `true`; or
     *     - the member shape is a structure or union shape.
     * Otherwise, `U` is always a `pub(crate)` tuple newtype holding `T`.
     *
     * For each member, this function first safely unwraps case 1. into 2., then converts `U` into `T` if necessary,
     * and then converts into b) if necessary.
     */
    private fun coreBuilder(writer: RustWriter) {
        writer.rustBlock("#T", structureSymbol) {
            for (member in members) {
                val memberName = symbolProvider.toMemberName(member)

                withBlock("$memberName: self.$memberName", ",") {
                    // Write the modifier(s).

                    // 1. Enforce constraint traits of data from incoming requests.
                    serverBuilderConstraintViolations.builderConstraintViolationForMember(member)?.also { constraintViolation ->
                        enforceConstraints(this, member, constraintViolation)
                    }

                    if (member.hasNonNullDefault()) {
                        // 2a. If a `@default` value is modeled and the user did not set a value, fall back to using the
                        // default value.
                        generateFallbackCodeToDefaultValue(
                            this,
                            member,
                            model,
                            runtimeConfig,
                            symbolProvider,
                            publicConstrainedTypes,
                        )
                    } else {
                        // 2b. If the member is `@required` and has no `@default` value, the user must set a value;
                        // otherwise, we fail with a `ConstraintViolation::Missing*` variant.
                        serverBuilderConstraintViolations.forMember(member)?.also {
                            rust(".ok_or(ConstraintViolation::${it.name()})?")
                        }
                    }
                }
            }
        }
    }

    private fun enforceConstraints(writer: RustWriter, member: MemberShape, constraintViolation: ConstraintViolation) {
        // This member is constrained. Enforce the constraint traits on the value set in the builder.
        // The code is slightly different in case the member is recursive, since it will be wrapped in
        // `std::boxed::Box`.
        val hasBox = builderMemberSymbol(member)
            .mapRustType { it.stripOuter<RustType.Option>() }
            .isRustBoxed()
        if (hasBox) {
            writer.rustTemplate(
                """
                .map(|v| match *v {
                    #{MaybeConstrained}::Constrained(x) => Ok(Box::new(x)),
                    #{MaybeConstrained}::Unconstrained(x) => Ok(Box::new(x.try_into()?)),
                })
                .map(|res|
                    res${ if (constrainedTypeHoldsFinalType(member)) "" else ".map(|v| v.into())" }
                       .map_err(|err| ConstraintViolation::${constraintViolation.name()}(Box::new(err)))
                )
                .transpose()?
                """,
                *codegenScope,
            )
        } else {
            writer.rustTemplate(
                """
                .map(|v| match v {
                    #{MaybeConstrained}::Constrained(x) => Ok(x),
                    #{MaybeConstrained}::Unconstrained(x) => x.try_into(),
                })
                .map(|res|
                    res${if (constrainedTypeHoldsFinalType(member)) "" else ".map(|v| v.into())"}
                       .map_err(ConstraintViolation::${constraintViolation.name()})
                )
                .transpose()?
                """,
                *codegenScope,
            )
        }

        // Constrained types are not public and this is a member shape that would have generated a
        // public constrained type, were the setting to be enabled.
        // We've just checked the constraints hold by going through the non-public
        // constrained type, but the user wants to work with the unconstrained type, so we have to
        // unwrap it.
        if (!publicConstrainedTypes && member.wouldHaveConstrainedWrapperTupleTypeWerePublicConstrainedTypesEnabled(model)) {
            writer.rust(
                ".map(|v: #T| v.into())",
                constrainedShapeSymbolProvider.toSymbol(model.expectShape(member.target)),
            )
        }
    }
}

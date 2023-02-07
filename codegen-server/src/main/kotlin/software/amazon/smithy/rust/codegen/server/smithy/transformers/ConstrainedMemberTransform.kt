package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.AbstractShapeBuilder
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.smithy.DirectedWalker
import software.amazon.smithy.rust.codegen.core.smithy.traits.SyntheticInputTrait
import software.amazon.smithy.rust.codegen.core.smithy.traits.SyntheticOutputTrait
import software.amazon.smithy.rust.codegen.server.smithy.traits.SyntheticStructureFromConstrainedMemberTrait
import software.amazon.smithy.utils.ToSmithyBuilder
import java.lang.IllegalStateException
import java.util.*
import software.amazon.smithy.rust.codegen.core.util.UNREACHABLE
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.allConstraintTraits
import software.amazon.smithy.rust.codegen.server.smithy.transformers.ConstrainedMemberTransform.makeNonConstrained

/**
 * Transforms all member shapes that have constraints on them into equivalent non-constrained
 * member shapes targeting synthetic constrained structure shapes with the member's constraints.
 *
 * E.g.:
 * ```
 * structure A {
 *   @length(min: 1, max: 69)
 *   string: ConstrainedString
 * }
 *
 * @length(min: 2, max: 10)
 * @pattern("^[A-Za-z]+$")
 * string ConstrainedString
 * ```
 *
 * to
 *
 * ```
 * structure A {
 *   string: OverriddenConstrainedString
 * }
 *
 * @length(min: 1, max: 69)
 * @pattern("^[A-Za-z]+$")
 * OverriddenConstrainedString
 *
 * @length(min: 2, max: 10)
 * @pattern("^[A-Za-z]+$")
 * string ConstrainedString
 * ```
 */
object ConstrainedMemberTransform {
    private data class MemberShapeTransformation(
        val newShape: Shape,
        val memberToChange: MemberShape,
        val traitsToKeep: List<Trait>,
    )

    private val memberConstraintTraitsToOverride = allConstraintTraits - RequiredTrait::class.java

    private fun Shape.hasMemberConstraintTrait() =
        memberConstraintTraitsToOverride.any(this::hasTrait)

    fun transform(model: Model): Model {
        val additionalNames = HashSet<ShapeId>()
        val walker = DirectedWalker(model)

        val transformations = model.operationShapes
            .flatMap { operation ->
                listOfNotNull(operation.input.orNull(), operation.output.orNull())
            }
            .mapNotNull { model.expectShape(it).asStructureShape().orElse(null) }
            .filter {
                // Restrict set of shapes to synthetic shapes that are added by OperationNormalizer as the
                // code is generated for these and not the one given as input model.
                it.hasTrait(SyntheticInputTrait.ID) || it.hasTrait(SyntheticOutputTrait.ID)
            }
            .flatMap {
                walker.walkShapes(it)
            }
            .filter {
                // Keep only the shapes that can have a constraint trait applied to them.
                it is StructureShape || it is ListShape || it is UnionShape || it is MapShape
            }
            .flatMap {
                it.constrainedMembers()
            }
            .mapNotNull {
                val transformation = it.makeNonConstrained(model, additionalNames)
                if (transformation != null) {
                    // Keep record of new names that have been generated to ensure none of them regenerated.
                    additionalNames.add(transformation.newShape.id)
                }

                transformation
            }

        return applyTransformations(model, transformations)
    }

    private fun constrainedMembersOfOperationReachableShapes(
        operationInputOutput: StructureShape,
        walker: DirectedWalker,
    ) =
    // Make a pair of ioShape -> set of all reachable shapes from it. The SyntheticTrait
    // that we need to put on each reachable shape needs to know the top most structure that
        // the shape is reachable from.
        Pair(
            operationInputOutput,
            walker.walkShapes(operationInputOutput)
                .filter {
                    // Keep only the shapes that can have a constraint trait applied to them.
                    // TODO: we can get rid of this filter part and just call the next flatMap
                    // as that should work on each shape that this OR expression has.
                    it is StructureShape || it is ListShape || it is UnionShape || it is MapShape
                }
                .flatMap {
                    it.constrainedMembers()
                },
        )


    /***
     * Returns a Model that has all the transformations applied on the original model.
     */
    private fun applyTransformations(
        model: Model,
        transformations: List<MemberShapeTransformation>,
    ): Model {
        val modelBuilder = model.toBuilder()

        val memberShapesToReplace = transformations.map {
            // Add the new shape to the model.
            modelBuilder.addShape(it.newShape)

            it.memberToChange.toBuilder()
                .target(it.newShape.id)
                .traits(it.traitsToKeep)
                .build()
        }

        // Change all original constrained member shapes with the new standalone shapes.
        return ModelTransformer.create()
            .replaceShapes(modelBuilder.build(), memberShapesToReplace)
    }

    /**
     * Returns a list of members that have constraint traits applied to them
     */
    private fun Shape.constrainedMembers(): List<MemberShape> =
        this.allMembers.values.filter {
            it.hasMemberConstraintTrait()
        }

    /**
     * Returns the unique (within the model) shape ID of the new shape
     */
    private fun overriddenShapeId(
        model: Model,
        additionalNames: Set<ShapeId>,
        memberShape: ShapeId,
    ): ShapeId {
        val structName = memberShape.name
        val memberName = memberShape.member.orElse(null)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        fun makeStructName(suffix: String = "") =
            ShapeId.from("${memberShape.namespace}#Overridden${structName}${memberName}$suffix")

        fun structNameIsUnique(newName: ShapeId) =
            model.getShape(newName).isEmpty && !additionalNames.contains(newName)

        fun generateUniqueName(): ShapeId {
            // Ensure the name does not already exist in the model, else make it unique
            // by appending a new number as the suffix.
            (0..100).forEach {
                val extractedStructName = if (it == 0) makeStructName("") else makeStructName("$it")
                if (structNameIsUnique(extractedStructName))
                    return extractedStructName
            }

            throw IllegalStateException("A unique name for the overridden structure type could not be generated")
        }

        return generateUniqueName()
    }

    /**
     * Returns the transformation that would be required to turn the given member shape
     * into a non-constrained member shape.
     */
    private fun MemberShape.makeNonConstrained(
        model: Model,
        additionalNames: MutableSet<ShapeId>,
    ): MemberShapeTransformation? {
        val (constraintTraits, otherTraits) = this.allTraits.values
            .partition {
                memberConstraintTraitsToOverride.contains(it.javaClass)
            }

        // No transformation required in case the member shape has no constraints.
        if (constraintTraits.isEmpty())
            return null

        // Build a new shape similar to the target of the constrained member shape. It should
        // have all of the original constraints that have not been overridden, and the ones
        // that this member shape overrides.
        val targetShape = model.expectShape(this.target)
        if (targetShape !is ToSmithyBuilder<*>)
            UNREACHABLE("member target shapes will always be buildable")

        return when (val builder = targetShape.toBuilder()) {
            is AbstractShapeBuilder<*, *> -> {
                // Use the target builder to create a new standalone shape that would
                // be added to the model later on. Keep all existing traits on the target
                // but replace the ones that are overridden on the member shape.
                val nonOverriddenTraitsOnTarget =
                    builder.allTraits.values.filter { existingTrait ->
                        constraintTraits.none { it.toShapeId() == existingTrait.toShapeId() }
                    }

                // Add a synthetic constraint on all new shapes being defined, that would link
                // the new shape to the root structure from which it is reachable.
                val syntheticTrait =
                    SyntheticStructureFromConstrainedMemberTrait(model.expectShape(this.container), this)

                // Combine target traits, overridden traits and the synthetic trait
                val newTraits =
                    nonOverriddenTraitsOnTarget + constraintTraits + syntheticTrait

                // Create a new unique standalone shape that will be added to the model later on
                val shapeId = overriddenShapeId(model, additionalNames, this.id)
                val standaloneShape = builder.id(shapeId)
                    .traits(newTraits)
                    .build()

                // Since the new shape has not been added to the model as yet, the current
                // memberShape's target cannot be changed to the new shape.
                MemberShapeTransformation(standaloneShape, this, otherTraits)
            }

            else -> UNREACHABLE("Constraint traits cannot to applied on ${this.id}")
        }
    }
}

package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.AbstractShapeBuilder
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.smithy.DirectedWalker
import software.amazon.smithy.rust.codegen.server.smithy.traits.SyntheticStructureFromConstrainedMemberTrait
import software.amazon.smithy.utils.ToSmithyBuilder
import java.lang.IllegalStateException
import java.util.*
import software.amazon.smithy.rust.codegen.core.util.UNREACHABLE
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.allConstraintTraits

/**
 * Transforms all member shapes that have constraints on them into equivalent non-constrained
 * member shapes targeting synthetic constrained structure shapes with the member's constraints.
 *
 * E.g.:
 *
 * structure A {
 *   @length(min: 1, max: 69)
 *   string: ConstrainedString
 * }
 *
 * @length(min: 2, max: 10)
 * @pattern("^[A-Za-z]+$")
 * string ConstrainedString
 *
 * to
 *
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

        val transformations = model.operationShapes
            .flatMap { operation ->
                listOfNotNull(operation.input.orNull(), operation.output.orNull())
            }
            .map { model.expectShape(it) }
            .flatMap {
                val walker = DirectedWalker(model)
                walker.walkShapes(it)
            }
            .filter { it.isStructureShape || it.isListShape || it.isUnionShape || it.isMapShape }
            .flatMap {
                it.constrainedMembers()
            }
            .mapNotNull {
                val transformation = it.makeNonConstrained(model, additionalNames)
                if (transformation != null)
                    additionalNames.add(transformation.newShape.id)

                transformation
            }

        return applyTransformations(model, transformations)
    }

    /***
     * Returns a Model that has all the transformations applied on the original model.
     */
    private fun applyTransformations(
        model: Model,
        transformations: List<MemberShapeTransformation>,
    ): Model {
        if (transformations.isEmpty())
            return model

        val modelBuilder = model.toBuilder()
        val memberShapesToChange: MutableList<MemberShape> = mutableListOf()

        transformations.forEach {
            modelBuilder.addShape(it.newShape)

            val changedMember = it.memberToChange.toBuilder()
                .target(it.newShape.id)
                .traits(it.traitsToKeep)
                .build()
            memberShapesToChange.add(changedMember)
        }

        // Change all original constrained member shapes with the new standalone types,
        // and keep only the non-constraint traits on the member shape.
        return ModelTransformer.create()
            .replaceShapes(modelBuilder.build(), memberShapesToChange)
    }

    /**
     * Returns a list of members that have constraint traits applied to them
     */
    private fun Shape.constrainedMembers(): List<MemberShape> =
        this.allMembers.values.filter {
            it.hasMemberConstraintTrait()
        }

    /**
     * Returns the unique (within the model) name of the new shape
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

        // No transformation required in case the given MemberShape has no constraints.
        if (constraintTraits.isEmpty())
            return null

        val targetShape = model.expectShape(this.target)
        if (targetShape !is ToSmithyBuilder<*>)
            UNREACHABLE("member target shapes will always be buildable")

        when (val builder = targetShape.toBuilder()) {
            is AbstractShapeBuilder<*, *> -> {
                // Use the target builder to create a new standalone shape that would
                // be added to the model later on. Keep all existing traits on the target
                // but replace the ones that are overridden on the member shapes.
                val existingNonOverriddenTraits =
                    builder.allTraits.values.filter { existingTrait ->
                        constraintTraits.none { it.toShapeId() == existingTrait.toShapeId() }
                    }

                val newTraits =
                    existingNonOverriddenTraits + constraintTraits + SyntheticStructureFromConstrainedMemberTrait(
                        this.id,
                    )

                // Create a new standalone shape that will be added to the model later on
                val shapeId = overriddenShapeId(model, additionalNames, this.id)
                val standaloneShape = builder.id(shapeId)
                    .traits(newTraits)
                    .build()

                // Since the new shape has not been added to the model as yet, the current
                // memberShape's target cannot be changed to the new shape.
                return MemberShapeTransformation(standaloneShape, this, otherTraits)
            }

            else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
        }

        throw IllegalStateException("Constraint traits can only be applied to buildable types. ${this.id} is not buildable") // FZ confirm how we are throwing exceptions
    }
}

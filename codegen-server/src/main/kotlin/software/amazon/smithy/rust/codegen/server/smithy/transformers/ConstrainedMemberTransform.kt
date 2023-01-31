package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.AbstractShapeBuilder
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.LengthTrait
import software.amazon.smithy.model.traits.PatternTrait
import software.amazon.smithy.model.traits.RangeTrait
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.model.traits.UniqueItemsTrait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.smithy.DirectedWalker
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.traits.RefactoredStructureTrait
import software.amazon.smithy.utils.ToSmithyBuilder
import java.lang.IllegalStateException
import java.util.*

object ConstrainedStructureMemberTransform {
    private data class MemberTransformation(
        val newShape: Shape,
        val memberToChange: MemberShape,
        val traitsToKeep: List<Trait>,
    )

    fun Shape.hasMemberConstraintTrait() =
        memberConstraintTraitsToOverride.any(this::hasTrait)

    private val memberConstraintTraitsToOverride = setOf(
        LengthTrait::class.java,
        PatternTrait::class.java,
        RangeTrait::class.java,
        UniqueItemsTrait::class.java,
        EnumTrait::class.java,
    )

    /**
     * Transforms all member shapes that have constraints on them into equivalent
     * non-constrained member shapes.
     */
    fun transform(model: Model): Model {
        val existingRefactoredNames = HashSet<ShapeId>()
        val walker = DirectedWalker(model)

        val transformations = model.operationShapes
            .flatMap { operation ->
                listOfNotNull(operation.input.orNull(), operation.output.orNull())
            }
            .map { model.expectShape(it) }
            .flatMap {
                walker.walkShapes(it)
            }
            .filter{ it.isStructureShape || it.isListShape || it.isUnionShape }
            .flatMap {
                it.constraintMembers()
            }.mapNotNull { it.makeNonConstrained(model, existingRefactoredNames) }

        return applyTransformations(model, transformations)
    }

    /***
     * Returns a Model that has all the transformations applied on the original
     * model.
     */
    private fun applyTransformations(
        model: Model,
        transformations: List<MemberTransformation>,
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
    private fun Shape.constraintMembers(): List<MemberShape> =
        this.allMembers.values.filter {
            it.hasMemberConstraintTrait()
        }

    /**
     * Returns the unique (within the model) name of the refactored shape
     */
    private fun refactoredShapeId(
        model: Model,
        existingRefactoredNames: MutableSet<ShapeId>,
        memberShape: ShapeId,
    ): ShapeId {
        val structName = memberShape.name
        val memberName = memberShape.member.orElse(null)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        fun makeStructName(suffix: String = "") =
            ShapeId.from("${memberShape.namespace}#Refactored${structName}${memberName}$suffix")

        fun structNameIsUnique(newName: ShapeId) =
            model.getShape(newName).isEmpty && !existingRefactoredNames.contains(newName)

        fun generateUniqueName(): ShapeId {
            // Ensure the name does not already exist in the model, else make it unique
            // by appending a new number as the suffix.
            var suffix = ""
            (0..100).forEach {
                val extractedStructName = makeStructName(suffix)
                if (structNameIsUnique(extractedStructName))
                    return extractedStructName

                suffix = "_$it"
            }

            // A unique type could not be found by adding a numeric suffix to it. As a
            // secondary algorithm, use existing struct names as suffix to generate a unique name.
            suffix = "_"

            model.listShapes
                .filterIsInstance<StructureShape>()
                .map {
                    suffix = suffix.plus(it.id.name)

                    val extractedStructName = makeStructName(suffix)
                    if (structNameIsUnique(extractedStructName))
                        return extractedStructName
                }

            throw IllegalStateException("A unique name for the refactored structure type could not be generated")
        }

        val newName = generateUniqueName()
        existingRefactoredNames.add(newName)
        return newName
    }

    /**
     * Returns the transformation that would be required to turn the given member shape
     * into a non-constrained member shape.
     */
    private fun MemberShape.makeNonConstrained(
        model: Model,
        existingRefactoredNames: MutableSet<ShapeId>,
    ): MemberTransformation? {
        val (constrainedTraits, otherTraits) = this.allTraits.values
            .partition {
                memberConstraintTraitsToOverride.contains(it.javaClass)
            }

        // No transformation required in case the given MemberShape has no constraints.
        if (constrainedTraits.isEmpty())
            return null

        // Create a new standalone shape of the same target as the target of the member e.g.
        // someX : Centigrade, where integer : Centigrade, then the new shape should be
        // integer: newShape
        val targetShape = model.expectShape(this.target)

        // The shape has to be a buildable type otherwise it cannot be refactored.
        if (targetShape is ToSmithyBuilder<*>) {
            when (val builder = targetShape.toBuilder()) {
                is AbstractShapeBuilder<*, *> -> {
                    // Use the target builder to create a new standalone shape that would
                    // be added to the model later on. Keep all existing traits on the target
                    // but replace the ones that the member shape overrides.
                    val existingNonOverridenTraits =
                        builder.allTraits.values.filter { existingTrait ->
                            !constrainedTraits.any { it.toShapeId() == existingTrait.toShapeId() }
                        }

                    val newTraits = existingNonOverridenTraits + constrainedTraits + RefactoredStructureTrait(this.id)

                    // Create a new standalone type that would be added to the model later on
                    val shapeId = refactoredShapeId(model, existingRefactoredNames, this.id)
                    val standaloneShape = builder.id(shapeId)
                        .traits(newTraits)
                        .build()

                    // Since the new shape has not been added to the model as yet, the current
                    // memberShape's target cannot be changed to the new shape.
                    // + OriginalShapeIdTrait(this.target)
                    return MemberTransformation(standaloneShape, this, otherTraits)
                }

                else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
            }
        }

        throw IllegalStateException("Constraint traits can only be applied to buildable types. ${this.id} is not buildable") // FZ confirm how we are throwing exceptions
    }
}

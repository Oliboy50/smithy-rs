package software.amazon.smithy.rust.codegen.server.smithy.traits

import software.amazon.smithy.model.SourceLocation
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.AbstractTrait
import software.amazon.smithy.model.traits.AbstractTraitBuilder
import software.amazon.smithy.model.traits.AnnotationTrait
import software.amazon.smithy.model.traits.PropertyTrait
import software.amazon.smithy.utils.ToSmithyBuilder
import java.util.*

/**
 * Trait applied to a refactored shape indicating the structure that contains member of this new structure type
 */
class RefactoredStructureTrait(val sourceMember : ShapeId): AnnotationTrait(RefactoredStructureTrait.ID, Node.objectNode())  {
    companion object {
        val ID : ShapeId = ShapeId.from("smithy.api.internal#refactoredMember")
    }
}

//class RefactoredStructureTrait(private val sourceMember : ShapeId) : AbstractTrait(RefactoredStructureTrait.ID, Node.objectNode()),
//    ToSmithyBuilder<RefactoredStructureTrait> {
//    companion object
//    {
//        val ID : ShapeId = ShapeId.from("smithy.api.internal#refactoredMember")
//    }
//
//    override  fun isSynthetic(): Boolean {
//        return true
//    }
//    override fun createNode(): Node {
//        val map = mutableMapOf<String, Node>(
//            "originalSource" to Node.from(sourceMember.toString()),
//        )
//        return ObjectNode(map.mapKeys { Node.from(it.key) }, SourceLocation.NONE)
//    }
//
//    fun builder(): Builder {
//        return Builder(sourceMember)
//    }
//
//    override fun toBuilder(): Builder? {
//        return builder().sourceLocation(sourceLocation)!!
//    }
//
//    class Builder(val sourceMember: ShapeId) :
//        AbstractTraitBuilder<PropertyTrait?, Builder?>() {
//        override fun build(): PropertyTrait {
//            return PropertyTrait(this)
//        }
//    }
//}

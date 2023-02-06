package software.amazon.smithy.rust.codegen.server.smithy

import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import java.util.concurrent.ConcurrentHashMap

private typealias Filename = String

open class InlineModuleWriter {
    /**
     * Keeps a mapping of InlineModule -> Writables that want to write to that inline module
     */
    class InlineModules {
        private val inlineModuleWritables: HashMap<RustModule.LeafModule, MutableList<Writable>> = HashMap()

        fun addWritable(module: RustModule.LeafModule, writable: Writable) {
            inlineModuleWritables.getOrPut(module) { mutableListOf() }
                .add(writable)
        }

        fun render(writer: RustWriter) {
            for ((inlineModule, writables) in inlineModuleWritables) {
                writer.withInlineModule(inlineModule) {
                    for (w in writables) {
                        w(this)
                    }
                }
            }
        }
    }

    // A mapping of Module -> InlineModules
    private val modules : HashMap<RustModule, InlineModules> = HashMap()

    fun add(fileModule : RustModule, inlineModule : RustModule.LeafModule, writeable : Writable) : InlineModuleWriter{
        modules.getOrPut(fileModule) { InlineModules() }
            .addWritable(inlineModule, writeable)
        return this
    }

    fun render(rustCrate: RustCrate) {
        for ((module, inlineModules) in modules) {
            rustCrate.withModule(module) {
                inlineModules.render(this)
            }
        }
    }
}

val crateToInlineModule : ConcurrentHashMap<RustCrate, InlineModuleWriter> = ConcurrentHashMap()

fun RustCrate.withModuleOrInlineForConstrainedMember(module : RustModule, shape: Shape, codegenContext: ServerCodegenContext, writeable : Writable) {
    useWriterOrInlineForConstrainedMember(shape, codegenContext, writeable) {
        this.withModule(module, it)
    }
}

fun RustCrate.useShapeWriterOrInlineForConstrainedMember(shape: Shape, codegenContext: ServerCodegenContext, writeable : Writable) {
    useWriterOrInlineForConstrainedMember(shape, codegenContext, writeable) {
        this.useShapeWriter(shape, it)
    }
}

fun RustCrate.withCombinedInlineModule(inlineModule: RustModule.LeafModule, writeable: Writable) {
    check(inlineModule.isInline()) {
        "module has to be an inline module for it to be used with the InlineModuleWriter"
    }
    val inlineWriter = crateToInlineModule.getOrPut(this) {InlineModuleWriter()}
    inlineWriter.add(inlineModule.parent, inlineModule) {
        writeable(this)
    }
}

fun RustCrate.renderInlineModules() {
    crateToInlineModule[this]?.render(this)
}

private fun RustCrate.useWriterOrInlineForConstrainedMember(shape: Shape, codegenContext: ServerCodegenContext, writeable : Writable, factory: (Writable) -> Unit) {
    val parentAndInlineModuleInfo = shape.getParentAndInlineModuleForConstrainedMember(codegenContext.symbolProvider)
    if (parentAndInlineModuleInfo == null) {
        factory(writeable)
    } else {
        val (parent, inline) = parentAndInlineModuleInfo
        val inlineWriter = crateToInlineModule.getOrPut(this) {InlineModuleWriter()}
        // Ensure the parent module exists by creating a RustWriter on it.
        this.withModule(parent) {
            inlineWriter.add(inline.parent, inline) {
                writeable(this)
            }
        }
    }
}

package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.NullableIndex
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.transformers.ConstrainedMemberTransform
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeCrateLocation
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.SymbolVisitorConfig
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.generatePluginContext
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.runCommand
import software.amazon.smithy.rust.codegen.core.util.toPascalCase
import software.amazon.smithy.rust.codegen.server.smithy.customize.CombinedServerCodegenDecorator
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerProtocolLoader
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestCodegenContext
import java.io.File
import java.nio.file.Path

class ConstraintsMemberShapeTest {
    // TODO Blob separate
    private val outputModelOnly = """
            namespace constrainedMemberShape

            use aws.protocols#restJson1
            use aws.api#data
    
            @restJson1
            service ConstrainedService {
                operations: [OperationUsingGet] 
            }
            
            @http(uri: "/anOperation", method: "GET")
            operation OperationUsingGet {
                output : OperationUsingGetOutput
            }
            structure OperationUsingGetOutput {
                plainLong : Long
                plainInteger : Integer
                plainShort : Short
                plainByte : Byte
                plainFloat: Float
                plainString: String

                @range(min: 1, max:100)
                constrainedLong : Long
                @range(min: 2, max:100)
                constrainedInteger : Integer
                @range(min: 3, max:100)
                constrainedShort : Short
                @range(min: 4, max:100)
                constrainedByte : Byte
                @length(max: 100)
                constrainedString: String
                
                @required
                @range(min: 5, max:100)
                requiredConstrainedLong : Long
                @required
                @range(min: 6, max:100)
                requiredConstrainedInteger : Integer
                @required
                @range(min: 7, max:100)
                requiredConstrainedShort : Short
                @required
                @range(min: 8, max:100)
                requiredConstrainedByte : Byte
                @required
                @length(max: 101)
                requiredConstrainedString: String
                
                patternString : PatternString
                
                @data("content")
                @pattern("^[g-m]+${'$'}")
                constrainedPatternString : PatternString

                plainStringList : PlainStringList
                patternStringList : PatternStringList
                patternStringListOverride : PatternStringListOverride
                
                plainStructField : PlainStructWithInteger
                structWithConstrainedMember : StructWithConstrainedMember
                structWithConstrainedMemberOverride : StructWithConstrainedMemberOverride
                
                patternUnion: PatternUnion
                patternUnionOverride: PatternUnionOverride
                patternMap : PatternMap
                patternMapOverride: PatternMapOverride
            }
            list ListWithIntegerMemberStruct {
                member: PlainStructWithInteger
            }
            structure PlainStructWithInteger {
                lat : Integer
                long : Integer
            }
            structure StructWithConstrainedMember {
                @range(min: 100)
                lat : Integer
                long : Integer
            }
            structure StructWithConstrainedMemberOverride {
                @range(min: 10)
                lat : RangedInteger
                @range(min: 10, max:100)
                long : RangedInteger
            }
            list PlainStringList {
                member: String
            }
            list PatternStringList {
                member: PatternString
            }
            list PatternStringListOverride {
                @pattern("^[g-m]+${'$'}")
                member: PatternString
            }
            map PatternMap {
                key: PatternString,
                value: PatternString
            }
            map PatternMapOverride {
                @pattern("^[g-m]+${'$'}")
                key: PatternString,
                @pattern("^[g-m]+${'$'}")
                value: PatternString
            }
            union PatternUnion {
                first: PatternString,
                second: PatternString
            }
            union PatternUnionOverride {
                @pattern("^[g-m]+${'$'}")
                first: PatternString,
                @pattern("^[g-m]+${'$'}")
                second: PatternString
            }
            @pattern("^[a-m]+${'$'}")
            string PatternString
            @range(min: 0, max:1000)
            integer RangedInteger
        """.asSmithyModel()

    private fun loadModel(model: Model): Model =
        ConstrainedMemberTransform.transform(OperationNormalizer.transform(outputModelOnly))

    @Test
    fun `non constrained fields should not be changed`() {
        val transformedModel = loadModel(outputModelOnly)

        fun checkFieldTargetRemainsSame(fieldName: String) {
            checkMemberShapeIsSame(
                transformedModel,
                outputModelOnly,
                "constrainedMemberShape.synthetic#OperationUsingGetOutput\$$fieldName",
                "constrainedMemberShape#OperationUsingGetOutput\$$fieldName",
            ) {
                "OperationUsingGetOutput$fieldName has changed whereas it is not constrained and should have remained same"
            }
        }

        setOf(
            "plainInteger",
            "plainLong",
            "plainByte",
            "plainShort",
            "plainFloat",
            "patternString",
            "plainStringList",
            "patternStringList",
            "patternStringListOverride",
            "plainStructField",
            "structWithConstrainedMember",
            "structWithConstrainedMemberOverride",
            "patternUnion",
            "patternUnionOverride",
            "patternMap",
            "patternMapOverride",
        ).forEach(::checkFieldTargetRemainsSame)

        checkMemberShapeIsSame(
            transformedModel,
            outputModelOnly,
            "constrainedMemberShape#StructWithConstrainedMember\$long",
            "constrainedMemberShape#StructWithConstrainedMember\$long",
        )
    }

    @Test
    fun `constrained members should have a different target now`() {
        val transformedModel = loadModel(outputModelOnly)
        checkMemberShapeChanged(
            transformedModel,
            outputModelOnly,
            "constrainedMemberShape#PatternStringListOverride\$member",
            "constrainedMemberShape#PatternStringListOverride\$member",
        )

        fun checkSyntheticFieldTargetChanged(fieldName: String) {
            checkMemberShapeChanged(
                transformedModel,
                outputModelOnly,
                "constrainedMemberShape.synthetic#OperationUsingGetOutput\$$fieldName",
                "constrainedMemberShape#OperationUsingGetOutput\$$fieldName",
            ) {
                "constrained member $fieldName should have been changed into a new type."
            }
        }

        fun checkFieldTargetChanged(memberNameWithContainer: String) {
            checkMemberShapeChanged(
                transformedModel,
                outputModelOnly,
                "constrainedMemberShape#$memberNameWithContainer",
                "constrainedMemberShape#$memberNameWithContainer",
            ) {
                "constrained member $memberNameWithContainer should have been changed into a new type."
            }
        }

        setOf(
            "constrainedLong",
            "constrainedByte",
            "constrainedShort",
            "constrainedInteger",
            "constrainedString",
            "requiredConstrainedString",
            "requiredConstrainedLong",
            "requiredConstrainedByte",
            "requiredConstrainedInteger",
            "requiredConstrainedShort",
            "constrainedPatternString",
        ).forEach(::checkSyntheticFieldTargetChanged)

        setOf(
            "StructWithConstrainedMember\$lat",
            "PatternMapOverride\$key",
            "PatternMapOverride\$value",
            "PatternStringListOverride\$member",
        ).forEach(::checkFieldTargetChanged)
    }

    @Test
    fun `extra trait on a constrained member should remain on it`() {
        val transformedModel = loadModel(outputModelOnly)
        checkShapeHasTrait(
            transformedModel,
            outputModelOnly,
            "constrainedMemberShape.synthetic#OperationUsingGetOutput\$constrainedPatternString",
            "constrainedMemberShape#OperationUsingGetOutput\$constrainedPatternString",
            "aws.api#data",
        )
    }

    private fun runServerCodeGen(model: Model, dirToUse: File? = null, writable: Writable): Path {
        val runtimeConfig =
            RuntimeConfig(runtimeCrateLocation = RuntimeCrateLocation.Path(File("../rust-runtime").absolutePath))

        val (context, dir) = generatePluginContext(
            model,
            runtimeConfig = runtimeConfig,
            overrideTestDir = dirToUse,
        )
        val codegenDecorator: CombinedServerCodegenDecorator =
            CombinedServerCodegenDecorator.fromClasspath(context)
        ServerCodegenVisitor(context, codegenDecorator)
            .execute()

        val codegenContext = serverTestCodegenContext(model)
        RustCrate(
            context.fileManifest,
            codegenContext.symbolProvider,
            ServerRustSettings.from(context.model, context.settings).codegenConfig
        )
            .lib { writable }

        return dir
    }

    @Test
    fun `new shapes are in the right modules`() {
        val dir = runServerCodeGen(outputModelOnly) {
            fun RustWriter.testTypeExistsInBuilderModule(typeName: String) {
                unitTest(
                    "builder_module_has_$typeName",
                    """
                    use crate::output::operation_using_get_output::$typeName;
                """,
                )
            }

            // All directly constrained members of the output structure should be in the builder module
            setOf(
                "constrainedLong",
                "constrainedByte",
                "constrainedShort",
                "constrainedInteger",
                "constrainedString",
                "requiredConstrainedString",
                "requiredConstrainedLong",
                "requiredConstrainedByte",
                "requiredConstrainedInteger",
                "requiredConstrainedShort",
                "constrainedPatternString",
            ).forEach(::testTypeExistsInBuilderModule)
        }

        val env = mapOf("RUSTFLAGS" to "-A dead_code")
        "cargo test".runCommand(dir, env)
    }

    /**
     *  Checks that the given member shape:
     *  1. Has been changed to a new shape
     *  2. New shape has the same type as the original shape's target e.g. float Centigrade,
     *     float newType
     */
    private fun checkMemberShapeChanged(
        model: Model,
        baseModel: Model,
        member: String,
        orgModelMember: String,
        lazyMessage: () -> Any = ::defaultError,
    ) {
        val memberId = ShapeId.from(member)
        check(model.getShape(memberId).isPresent, lazyMessage)
        val memberShape = model.expectShape(memberId).asMemberShape().get()
        val memberTargetShape = model.expectShape(memberShape.target)
        val orgMemberId = ShapeId.from(orgModelMember)
        check(baseModel.getShape(orgMemberId).isPresent, lazyMessage)
        val originalShape = baseModel.expectShape(orgMemberId).asMemberShape().get()
        val originalTargetShape = model.expectShape(originalShape.target)

        val extractableConstraintTraits = allConstraintTraits - RequiredTrait::class.java

        // New member shape should not have the overridden constraints on it
        check(!extractableConstraintTraits.any(memberShape::hasTrait), lazyMessage)

        // Target shape has to be changed to a new shape
        check(memberTargetShape.id.name != originalShape.target.name, lazyMessage)
        // Target shape's name should match the expected name
        val expectedName = memberShape.container.name.substringAfter('#') +
            memberShape.memberName.substringBefore('#').toPascalCase()
        check(memberTargetShape.id.name == expectedName, lazyMessage)
        // New shape should have all of the constraint traits that were on the member shape,
        // and it should also have the traits that the target type contains.
        val originalConstrainedTraits =
            originalShape.allTraits.values.filter { allConstraintTraits.contains(it.javaClass) }.toSet()
        val newShapeConstrainedTraits =
            memberTargetShape.allTraits.values.filter { allConstraintTraits.contains(it.javaClass) }.toSet()

        val leftOutConstraintTrait = originalConstrainedTraits - newShapeConstrainedTraits
        check(
            leftOutConstraintTrait.isEmpty() || leftOutConstraintTrait.all {
                it.toShapeId() == RequiredTrait.ID
            },
            lazyMessage,
        )

        // In case the target shape has some more constraints, which the member shape did not override,
        // then those still need to apply on the new standalone shape that has been defined.
        val leftOverTraits = originalTargetShape.allTraits.values
            .filter { beforeOverridingTrait -> originalConstrainedTraits.none { beforeOverridingTrait.toShapeId() == it.toShapeId() } }
        val allNewShapeTraits = memberTargetShape.allTraits.values.toList()
        check((leftOverTraits + newShapeConstrainedTraits).all { it in allNewShapeTraits }, lazyMessage)
    }

    private fun defaultError() = "test failed"

    /**
     * Checks that the given shape has not changed in the transformed model and is exactly
     * the same as the original model
     */
    private fun checkMemberShapeIsSame(
        model: Model,
        baseModel: Model,
        member: String,
        orgModelMember: String,
        lazyMessage: () -> Any = ::defaultError,
    ) {
        val memberId = ShapeId.from(member)
        check(model.getShape(memberId).isPresent, lazyMessage)

        val memberShape = model.expectShape(memberId).asMemberShape().get()
        val memberTargetShape = model.expectShape(memberShape.target)
        val originalShape = baseModel.expectShape(ShapeId.from(orgModelMember)).asMemberShape().get()

        // Member shape should not have any constraints on it
        check(!memberShape.hasConstraintTrait(), lazyMessage)
        // Target shape has to be same as the original shape
        check(memberTargetShape.id == originalShape.target, lazyMessage)
    }

    private fun checkShapeHasTrait(
        model: Model,
        orgModel: Model,
        member: String,
        orgModelMember: String,
        traitName: String,
    ) {
        val memberId = ShapeId.from(member)
        val memberShape = model.expectShape(memberId).asMemberShape().get()
        val orgMemberShape = orgModel.expectShape(ShapeId.from(orgModelMember)).asMemberShape().get()

        check(memberShape.allTraits.keys.contains(ShapeId.from(traitName)))
        { "given $member does not have the $traitName applied to it" }
        check(orgMemberShape.allTraits.keys.contains(ShapeId.from(traitName)))
        { "given $member does not have the $traitName applied to it in the original model" }

        val newMemberTrait = memberShape.allTraits[ShapeId.from(traitName)]
        val oldMemberTrait = orgMemberShape.allTraits[ShapeId.from(traitName)]
        check(newMemberTrait == oldMemberTrait) { "contents of the two traits do not match in the transformed model" }
    }

    private fun checkShapeTargetMatches(model: Model, member: String, targetShapeId: String) =
        check(
            model.expectShape(ShapeId.from(member)).asMemberShape()
                .get().target.name == ShapeId.from(targetShapeId).name,
        )
}

//    private val testBareModel = """
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "GET")
//            operation GetWeather {
//                output : WeatherOutput
//            }
//
//            structure WeatherOutput {
//                @range(max: 200)
//                degree : Centigrade
//
//                feels : FeelsLike
//
//                @range(min: -10)
//                realFeeling: FeelsLike
//            }
//
//            @length(max: 10)
//            list StringList {
//                member : String
//            }
//
//            list PatternListOverride {
//                @pattern("^[g-m]+${'$'}")
//                member: PatternString
//            }
//
//            @pattern("^[a-m]+${'$'}")
//            string PatternString
//
//            integer Centigrade
//
//            @range(min: -100, max:100)
//            integer FeelsLike
//        """.trimIndent().asSmithyModel()
//
//    @Test
//    fun `single overridden fields in output`() {
//        val model = """
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "POST")
//            operation GetWeather {
//                input : WeatherInput
//                output : WeatherOutput
//            }
//
//            structure WeatherInput {
//                cities : String
//            }
//
//            structure WeatherOutput {
//                @range(max: 200)
//                degree : Centigrade
//            }
//
//            integer Centigrade
//        """.trimIndent().asSmithyModel()
//
//        generateCode(model, File("/Users/fahadzub/workplace/baykar/smithy")) {}
//    }
//
//    @Test
//    fun `string stand alone constrained type should not end up in builder`() {
//        val model = """
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "GET")
//            operation GetWeather {
//                output : WeatherOutput
//            }
//
//            structure WeatherOutput {
//                cities : CityName
//            }
//
//            @length(max:100)
//            string CityName
//        """.trimIndent().asSmithyModel()
//
//        generateCode(model) {}
//    }
//
//    @Test
//    fun `string member constrained type should end up in builder`() {
//        val model = """
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "GET")
//            operation GetWeather {
//                output : WeatherOutput
//            }
//
//            structure WeatherOutput {
//                @length(min: 1, max: 200)
//                cities : CityName
//            }
//
//            @length(max:100)
//            string CityName
//        """.trimIndent().asSmithyModel()
//
//        generateCode(model, File("/Users/fahadzub/workplace/baykar/smithy")) {}
//
//        // test - this should go as a stand alone type not an overridden type
//    }
//
//    @Test
//    fun `two overridden fields in output`() {
//        val modelWithTwoOverridden = """
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "POST")
//            operation GetWeather {
//                input : WeatherInput
//                output : WeatherOutput
//            }
//
//            structure WeatherInput {
//                cities : String
//            }
//
//            structure WeatherOutput {
//                @range(max: 200)
//                degree : Centigrade
//
//                @range(max: 100)
//                degreeFeelsLike: Centigrade
//            }
//
//            integer Centigrade
//        """.trimIndent().asSmithyModel()
//
//        generateCode(modelWithTwoOverridden) {}
//    }
//
//    @Test
//    fun `simple list overridden model`() {
//        val listModel = """
//        ${'$'}version: "1.0"
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "GET")
//            operation GetWeather {
//                output : WeatherOutput
//            }
//
//            structure WeatherOutput {
//                @length(min: 1)
//                months : StringList
//                cities : CityListOverride
//            }
//
//            @length(max: 10)
//            list StringList {
//                member : String
//            }
//
//            list CityListOverride {
//                @pattern("a-i")
//                member: CityName
//            }
//
//            @pattern("j-z")
//            string CityName
//        """.trimIndent().asSmithyModel()
//
//        generateCode(listModel) {}
//    }
//
//    @Test
//    fun `two field and simple list overridden model`() {
//        val listModel = """
//        ${'$'}version: "1.0"
//            namespace weather
//
//            use aws.api#data
//            use aws.protocols#restJson1
//
//            @title("Weather Service")
//            @restJson1
//            service WeatherService {
//                operations: [GetWeather]
//                errors: []
//            }
//
//            @http(uri: "/weather", method: "GET")
//            operation GetWeather {
//                output: WeatherOutput
//            }
//
//            structure WeatherOutput {
//                @range(max: 200)
//                degree : Centigrade
//
//                feels : FeelsLike
//
//                @range(min: -10)
//                realFeeling: FeelsLike
//
//                @length(min: 1)
//                months : StringList
//
//                cities : CityListOverride
//            }
//
//            @length(max: 10)
//            list StringList {
//                member : String
//            }
//
//            list CityListOverride {
//                @pattern("a-i")
//                member: CityName
//            }
//
//            @pattern("j-z")
//            string CityName
//
//
//            @length(max: 10)
//            list StringList {
//                member : String
//            }
//
//            @pattern("^[a-m]+${'$'}")
//            string PatternString
//
//            integer Centigrade
//
//            @range(min: -100, max:100)
//            integer FeelsLike
//        """.trimIndent().asSmithyModel()
//
//        generateCode(listModel) {}
//    }
//
//    private fun generateCode(model: Model, dirToUse: File? = null, carryOutTest: (Path) -> Unit) {
//        val runtimeConfig =
//            RuntimeConfig(runtimeCrateLocation = RuntimeCrateLocation.Path(File("../rust-runtime").absolutePath))
//
//        val (context, dir) = generatePluginContext(
//            model,
//            runtimeConfig = runtimeConfig,
//            overrideTestDir = dirToUse,
//        )
//        val codegenDecorator: CombinedServerCodegenDecorator =
//            CombinedServerCodegenDecorator.fromClasspath(context)
//        ServerCodegenVisitor(context, codegenDecorator)
//            .execute()
//
//        carryOutTest(dir)
//    }
//
//    private val simpleModelWithNoConstraints = """
//        namespace weather
//
//        use aws.protocols#restJson1
//
//        @restJson1
//        service WeatherService {
//            operations: [GetWeather]
//        }
//
//        @http(uri: "/city-weather", method: "POST")
//        operation GetWeather {
//            input : WeatherInput
//        }
//
//        structure WeatherInput {
//            latitude : Float
//            longitude: Float
//        }
//    """.asSmithyModel()
//
//    /**
//     * Checks there are no side effects off running the transformation on a model
//     * that has no constraint types in it at all
//     */
//    @Test
//    fun `Running transformations on a model without constraints has no side effects`() {
//        val model = ConstrainedMemberTransform.transform(simpleModelWithNoConstraints)
//        simpleModelWithNoConstraints.let {
//            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$latitude", "weather#WeatherInput\$latitude")
//            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$longitude", "weather#WeatherInput\$longitude")
//        }
//
//        generateCode(simpleModelWithNoConstraints) {}
//    }
//
//    /**
//     * Checks there are no side effects off running the transformation on a model
//     * that has no constraint types in it at all
//     */
//    @Test
//    fun `Running transformations on a model with no member constraints has no side effects`() {
//        val simpleModelWithNoMemberConstraints = """
//            namespace weather
//
//            use aws.protocols#restJson1
//
//            @restJson1
//            service WeatherService {
//                operation: [GetWeather]
//            }
//
//            operation GetWeather {
//                input : WeatherInput
//            }
//
//            structure WeatherInput {
//                latitude : Latitude
//                longitude: Longitude
//            }
//
//            @range(min:-90, max:90)
//            float Latitude
//            @range(min:-180, max:180)
//            float Longitude
//        """.asSmithyModel()
//
//        val model = ConstrainedMemberTransform.transform(simpleModelWithNoMemberConstraints)
//        simpleModelWithNoMemberConstraints.let {
//            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$latitude", "weather#WeatherInput\$latitude")
//            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$longitude", "weather#WeatherInput\$longitude")
//        }
//    }
//
//    /**
//     * Checks there are no side effects off running the transformation on a model
//     * that has one empty operation in it.
//     */
//    @Test
//    fun `Model with an additional input output  works`() {
//        val modelWithAnEmptyOperation = """
//            namespace weather
//
//            use aws.protocols#restJson1
//
//            @restJson1
//            service WeatherService {
//                operation: [GetWeather,Test]
//            }
//
//            operation GetWeather {
//                input : WeatherInput
//            }
//
//            operation Test {
//            }
//
//            structure WeatherInput {
//                latitude : Latitude
//                longitude: Longitude
//            }
//
//            @range(min:-90, max:90)
//            float Latitude
//            @range(min:-180, max:180)
//            float Longitude
//        """.asSmithyModel()
//
//        val model = ConstrainedMemberTransform.transform(modelWithAnEmptyOperation)
//        modelWithAnEmptyOperation.let {
//            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$latitude", "weather#WeatherInput\$latitude")
//            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$longitude", "weather#WeatherInput\$longitude")
//        }
//    }
//
//    /**
//     * Checks that a model with only an empty operation works
//     */
//    @Test
//    fun `Empty operation model works`() {
//        val modelWithOnlyEmptyOperation = """
//            namespace weather
//
//            use aws.protocols#restJson1
//
//            @restJson1
//            service WeatherService {
//                operation: [Test]
//            }
//
//            operation Test {
//            }
//        """.asSmithyModel()
//
//        val modelT = ConstrainedMemberTransform.transform(modelWithOnlyEmptyOperation)
//        check(modelWithOnlyEmptyOperation == modelT)
//    }
//
//    @Test
//    fun `generate code for a small struct with member shape`() {
//        val codeGenModel = """
//            namespace com.aws.example.rust
//
//            use aws.protocols#restJson1
//
//            /// The Pokémon Service allows you to retrieve information about Pokémon species.
//            @title("Pokémon Service")
//            @restJson1
//            service PokemonService {
//                version: "2021-12-01",
//                operations: [
//                    Dummy
//                ],
//            }
//
//            @http(uri: "/pokemon-species", method: "POST")
//            operation Dummy {
//                input : DummyInput
//            }
//
//            structure DummyInput {
//                @range(min: -10, max:10)
//                degree : Centigrade
//            }
//
//            integer Centigrade
//            """.asSmithyModel()
//
//        val runtimeConfig =
//            RuntimeConfig(runtimeCrateLocation = RuntimeCrateLocation.Path(File("../../rust-runtime").absolutePath))
//
//        val (context, _testDir) = generatePluginContext(
//            codeGenModel,
//            runtimeConfig = runtimeConfig,
//            overrideTestDir = File("/Users/fahadzub/workplace/baykar/smithy"),
//        )
//        val codegenDecorator: CombinedServerCodegenDecorator =
//            CombinedServerCodegenDecorator.fromClasspath(context)
//
//        ServerCodegenVisitor(context, codegenDecorator)
//            .execute()
//
////        val modelToSerialize = context.model
////        val serializer: ModelSerializer = ModelSerializer.builder().build()
////        val json = Node.prettyPrintJson(serializer.serialize(modelToSerialize))
////        File("/Users/fahadzub/workplace/baykar/smithy/model.json").printWriter().use {
////            it.println(json)
////        }
////
////        println("Check $_testDir, that should have the code in it")
//    }
//
//
//
//    @Test
//    fun `test malformed model`() {
//        val malformedModel = """
//            namespace test
//
//            @suppress(["UnstableTrait"])
//            @http(uri: "/MalformedRangeOverride", method: "POST")
//            operation MalformedRangeOverride {
//                input: MalformedRangeOverrideInput,
//            }
//
//            structure MalformedRangeOverrideInput {
//                @range(min: 4, max: 6)
//                short: RangeShort,
//                @range(min: 4)
//                minShort: MinShort,
//                @range(max: 6)
//                maxShort: MaxShort,
//
//                @range(min: 4, max: 6)
//                integer: RangeInteger,
//                @range(min: 4)
//                minInteger: MinInteger,
//                @range(max: 6)
//                maxInteger: MaxInteger,
//
//                @range(min: 4, max: 6)
//                long: RangeLong,
//                @range(min: 4)
//                minLong: MinLong,
//                @range(max: 6)
//                maxLong: MaxLong,
//            }
//
//            @range(min: 2, max: 8)
//            short RangeShort
//
//            @range(min: 2)
//            short MinShort
//
//            @range(max: 8)
//            short MaxShort
//
//            @range(min: 2, max: 8)
//            integer RangeInteger
//
//            @range(min: 2)
//            integer MinInteger
//
//            @range(max: 8)
//            integer MaxInteger
//
//            @range(min: 2, max: 8)
//            long RangeLong
//
//            @range(min: 2)
//            long MinLong
//
//            @range(max: 8)
//            long MaxLong
//            """.asSmithyModel()
//        val model = ConstrainedMemberTransform.transform(malformedModel)
//        checkShapeTargetMatches(
//            model,
//            "test#MalformedRangeOverrideInput\$short",
//            "test#OverriddenMalformedRangeOverrideInputshort",
//        )
//    }
//}

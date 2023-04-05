/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 Pedro Alves
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.dropProject.data

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonView
import org.dropProject.dao.Assignment
import org.dropProject.dao.Language
import org.dropProject.services.JUnitMethodResult
import org.dropProject.services.JUnitMethodResultType
import org.dropProject.services.JUnitResults
import org.dropProject.services.JacocoResults
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Enum representing the types of tests that DP supports:
 * - Student tests - unit tests written by the students to test their own work;
 * - Teacher - unit tests written by the teachers to test the student's work; The detailed results of these tests are
 * always shown to the students.
 * - Hidden Teacher Tests - unit tests written by the teachers; The results of these tests can be partially visible to
 * the students or not (configurable when creating the assignment).
 */
enum class TestType {
    STUDENT, TEACHER, HIDDEN
}

/**
 * Represents the output that is generated by Maven for a certain [Submission]'s code.
 *
 * @property mavenOutputLines is a List of String, where each String is a line of the Maven's build process's output
 * @property mavenizedProjectFolder is a String
 * @property assignment identifies the [Assignment] that Submission targetted.
 * @property junitResults is a List of [JunitResults] with the result of evaluating the Submission using JUnit tests
 * @property jacocoResults is a List of [JacocoResults] with the result of evaluating the Submission's code coverage
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)  // exclude nulls and empty fields from serialization
data class BuildReport(val mavenOutputLines: List<String>,
                       val mavenizedProjectFolder: String,
                       val assignment: Assignment,
                       val junitResults: List<JUnitResults>,
                       val jacocoResults: List<JacocoResults>) {

    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    @JsonView(JSONViews.StudentAPI::class)
    val compilationErrors: List<String>

    @JsonView(JSONViews.StudentAPI::class)
    val checkstyleErrors: List<String>

    @JsonView(JSONViews.StudentAPI::class)
    val junitSummaryStudent: String?
    @JsonView(JSONViews.StudentAPI::class)
    val junitErrorsStudent: String?

    @JsonView(JSONViews.StudentAPI::class)
    val junitSummaryTeacher: String?
    @JsonView(JSONViews.StudentAPI::class)
    val junitSummaryTeacherExtraDescription: String?
    @JsonView(JSONViews.StudentAPI::class)
    val junitErrorsTeacher: String?

    val junitSummaryHidden: String?
    val junitErrorsHidden: String?

    init {
        compilationErrors = processCompilationErrors()
        checkstyleErrors = processCheckstyleErrors()
        junitSummaryStudent = junitSummary(TestType.STUDENT)
        junitErrorsStudent = jUnitErrors(TestType.STUDENT)
        junitSummaryTeacher = junitSummary(TestType.TEACHER)
        junitSummaryTeacherExtraDescription = junitSummaryExtraDescription(TestType.TEACHER)
        junitErrorsTeacher = jUnitErrors(TestType.TEACHER)
        junitSummaryHidden = junitSummary(TestType.HIDDEN)
        junitErrorsHidden = jUnitErrors(TestType.HIDDEN)
    }

    fun mavenOutput() : String {
        return mavenOutputLines.joinToString(separator = "\n")
    }

    fun mavenExecutionFailed() : Boolean {
        // if it has a failed goal other than compiler or surefire (junit), it is a fatal error
        if (mavenOutputLines.
                        filter { it.startsWith("[ERROR] Failed to execute goal") }.isNotEmpty()) {
            return mavenOutputLines.filter {
                        it.startsWith("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin") ||
                        it.startsWith("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin") ||
                        it.startsWith("[ERROR] Failed to execute goal org.jetbrains.kotlin:kotlin-maven-plugin")
            }.isEmpty()
        }
        return false;
    }

    /**
     * Collects from a Maven Output the errors related with the Compilation process.
     *
     * @return a List of String where each String is a Compilation problem / warning.
     */
    private fun processCompilationErrors() : List<String> {

        var errors = ArrayList<String>()

        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        // parse compilation errors
        run {
            val triggerStartOfCompilationOutput =
                    if (assignment.language == Language.JAVA)
                        "\\[ERROR\\] COMPILATION ERROR :.*".toRegex()
                    else
                        "\\[INFO\\] --- kotlin-maven-plugin:\\d+\\.\\d+\\.\\d+:compile.*".toRegex()

            var startIdx = -1;
            var endIdx = -1;
            for ((idx, mavenOutputLine) in mavenOutputLines.withIndex()) {
                if (triggerStartOfCompilationOutput.matches(mavenOutputLine)) {
                    startIdx = idx + 1
                    LOG.trace("Found start of compilation output (line $idx)")
                } else if (startIdx > 0) {
                    if (mavenOutputLine.startsWith("[INFO] BUILD FAILURE") ||
                            mavenOutputLine.startsWith("[INFO] --- ")) {    // no compilation errors on Kotlin
                        endIdx = idx
                        LOG.trace("Found end of compilation output (line $idx)")
                        break
                    }
                }
            }

            if (startIdx > 0 && endIdx > startIdx) {
                errors.addAll(
                        mavenOutputLines
                                .subList(startIdx, endIdx)
                                .filter { it -> it.startsWith("[ERROR] ") || it.startsWith("  ") }
                                .map { it -> it.replace("[ERROR] ${mavenizedProjectFolder}/src/main/${folder}/", "") }
                                .map { it -> it.replace("[ERROR] ${mavenizedProjectFolder}/src/test/${folder}/", "[TEST] ") })
            }
        }

        // parse test compilation errors
        run {
            val triggerStartOfTestCompilationOutput =
//                    if (language == Language.JAVA) "???" else
                        "\\[ERROR\\] Failed to execute goal org\\.jetbrains\\.kotlin:kotlin-maven-plugin.*test-compile.*".toRegex()

            var startIdx = -1;
            var endIdx = -1;
            for ((idx, mavenOutputLine) in mavenOutputLines.withIndex()) {
                if (triggerStartOfTestCompilationOutput.matches(mavenOutputLine)) {
                    startIdx = idx + 1
                }
                if (mavenOutputLine.startsWith("[ERROR] -> [Help 1]")) {
                    endIdx = idx
                }
            }

            if (startIdx > 0) {
                errors.addAll(
                        mavenOutputLines
                                .subList(startIdx, endIdx)
                                .filter { it -> it.startsWith("[ERROR] ") || it.startsWith("  ") }
                                .map { it -> it.replace("[ERROR] ${mavenizedProjectFolder}/src/main/${folder}/", "") }
                                .map { it -> it.replace("[ERROR] ${mavenizedProjectFolder}/src/test/${folder}/", "[TEST] ") })
            }
        }

        // check if tests didn't run because of a crash or System.exit(). for the lack of better solution, I'll
        // consider this as a compilation error
        if (mavenOutputLines.any { it.contains("The forked VM terminated without properly saying goodbye.") }) {
           when (assignment.language) {
               Language.JAVA -> errors.add("Invalid call to System.exit(). Please remove this instruction")
               Language.KOTLIN ->  errors.add("Invalid call to System.exit() or exitProcess(). Please remove this instruction")
            }
        }

        return errors
    }

    /**
     * Collects from a Maven Output the errors related with the CheckStyle plugin / rules.
     *
     * @return a List of String where each String is a CheckStyle problem / warning.
     */
    private fun processCheckstyleErrors() : List<String> {

        val folder = if (assignment.language == Language.JAVA) "java" else "kotlin"

        when (assignment.language) {
            Language.JAVA -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, mavenOutputLine) in mavenOutputLines.withIndex()) {
                    if (mavenOutputLine.startsWith("[INFO] Starting audit...")) {
                        startIdx = idx + 1
                    }
                    if (mavenOutputLine.startsWith("Audit done.")) {
                        endIdx = idx
                    }
                }

                if (startIdx > 0) {
                    return mavenOutputLines
                            .subList(startIdx, endIdx)
                            .filter { it -> it.startsWith("[WARN] ") }
                            .map { it -> it.replace("[WARN] ${mavenizedProjectFolder}/src/main/${folder}/", "") }
                } else {
                    return emptyList()
                }
            }

            Language.KOTLIN -> {
                var startIdx = -1;
                var endIdx = -1;
                for ((idx, mavenOutputLine) in mavenOutputLines.withIndex()) {
                    if (mavenOutputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        startIdx = idx + 1
                    }
                    // depending on the detekt-maven-plugin version, the output is different
                    if (startIdx > 0 &&
                            idx > startIdx + 1 &&
                            (mavenOutputLine.startsWith("detekt finished") || mavenOutputLine.startsWith("[INFO]"))) {
                        endIdx = idx
                        break
                    }
                }

                if (startIdx > 0) {
                    return mavenOutputLines
                            .subList(startIdx, endIdx)
                            .filter { it.startsWith("\t") && !it.startsWith("\t-") }
                            .map { it.replace("\t", "") }
                            .map { it.replace("${mavenizedProjectFolder}/src/main/${folder}/", "") }
                            .map { translateDetektError(it) }
                            .distinct()
                } else {
                    return emptyList()
                }
            }
        }
    }

    fun checkstyleValidationActive() : Boolean {
        when (assignment.language) {
            Language.JAVA -> {
                for (mavenOutputLine in mavenOutputLines) {
                    if (mavenOutputLine.startsWith("[INFO] Starting audit...")) {
                        return true
                    }
                }

                return false
            }
            Language.KOTLIN -> {
                for (mavenOutputLine in mavenOutputLines) {
                    if (mavenOutputLine.startsWith("[INFO] --- detekt-maven-plugin")) {
                        return true
                    }
                }

                return false
            }
        }
    }

    fun PMDerrors() : List<String> {
        return mavenOutputLines
                .filter { it -> it.startsWith("[INFO] PMD Failure") }
                .map { it -> it.substring(19) }  // to remove "[INFO] PMD Failure: "
    }

    private fun junitSummary(testType: TestType = TestType.TEACHER) : String? {

        val junitSummary = junitSummaryAsObject(testType)
        if (junitSummary != null) {
            return "Tests run: ${junitSummary.numTests}, Failures: ${junitSummary.numFailures}, " +
                    "Errors: ${junitSummary.numErrors}, Time elapsed: ${junitSummary.ellapsed} sec"
        } else {
            return null
        }
    }

    private fun junitSummaryExtraDescription(testType: TestType = TestType.TEACHER) : String? {

        val junitSummary = junitSummaryAsObject(testType)
        if (junitSummary != null) {
            if (!assignment.mandatoryTestsSuffix.isNullOrEmpty() && junitSummary.numMandatoryNOK > 0) {
                return "Important: you are still failing ${junitSummary.numMandatoryNOK} mandatory tests"
            }
        }

        return null
    }

    /**
     * Creates a summary of the testing results, considering a certain [TestType].
     *
     * @param testType is a [TestType], indicating which tests should be considered (e.g TEACHER tests)
     *
     * @return a [JUnitSummary]
     */
    fun junitSummaryAsObject(testType: TestType = TestType.TEACHER) : JUnitSummary? {

        if (junitResults
                .filter{testType == TestType.TEACHER && it.isTeacherPublic(assignment) ||
                        testType == TestType.STUDENT && it.isStudent(assignment) ||
                        testType == TestType.HIDDEN && it.isTeacherHidden()}
                .isEmpty()) {
            return null
        }

        var totalTests = 0
        var totalErrors = 0
        var totalFailures = 0
        var totalSkipped = 0
        var totalElapsed = 0.0f
        var totalMandatoryOK = 0  // mandatory tests that passed
        var totalMandatoryNOK = 0  // mandatory tests that failed

        for (junitResult in junitResults) {

            if (testType == TestType.TEACHER && junitResult.isTeacherPublic(assignment) ||
                    testType == TestType.STUDENT && junitResult.isStudent(assignment) ||
                    testType == TestType.HIDDEN && junitResult.isTeacherHidden()) {
                totalTests += junitResult.numTests
                totalErrors += junitResult.numErrors
                totalFailures += junitResult.numFailures
                totalSkipped += junitResult.numSkipped
                totalElapsed += junitResult.timeEllapsed

                assignment.mandatoryTestsSuffix?.let {
                    mandatoryTestsSuffix ->
                    if (mandatoryTestsSuffix.isNotEmpty()) {
                        totalMandatoryOK += junitResult.junitMethodResults.count {
                            it.fullMethodName.endsWith(mandatoryTestsSuffix) && it.type == JUnitMethodResultType.SUCCESS
                        }
                        totalMandatoryNOK += junitResult.junitMethodResults.count {
                            it.fullMethodName.endsWith(mandatoryTestsSuffix) && it.type != JUnitMethodResultType.SUCCESS
                        }
                    }
                }
            }
        }

        return JUnitSummary(totalTests, totalFailures, totalErrors, totalSkipped, totalElapsed, totalMandatoryOK, totalMandatoryNOK)

    }

    /**
     * Calculates the total elapsed time during the execution of the Unit Tests. Considers both the public and the
     * private (hidden) tests.
     *
     * @return a BigDecimal representing the elapsed time
     */
    fun elapsedTimeJUnit() : BigDecimal? {
        var total : BigDecimal? = null
        val junitSummaryTeacher = junitSummaryAsObject(TestType.TEACHER)
        if (junitSummaryTeacher != null) {
            total = junitSummaryTeacher.ellapsed.toBigDecimal()
        }

        val junitSummaryHidden = junitSummaryAsObject(TestType.HIDDEN)
        if (junitSummaryHidden != null && total != null) {
            total += junitSummaryHidden.ellapsed.toBigDecimal()
        }

        return total
    }

    /**
     * Determines if the evaluation resulted in any JUnit errors or failures.
     */
    fun hasJUnitErrors(testType: TestType = TestType.TEACHER) : Boolean? {
        val junitSummary = junitSummaryAsObject(testType)
        if (junitSummary != null) {
            return junitSummary.numErrors > 0 || junitSummary.numFailures > 0
        } else {
            return null
        }
    }

    /**
     * Determines if the evaluation resulted in any JUnit errors or failures.
     */
    private fun jUnitErrors(testType: TestType = TestType.TEACHER) : String? {
        var result = ""
        for (junitResult in junitResults) {
            if (testType == TestType.TEACHER && junitResult.isTeacherPublic(assignment) ||
                    testType == TestType.STUDENT && junitResult.isStudent(assignment) ||
                    testType == TestType.HIDDEN && junitResult.isTeacherHidden()) {
                result += junitResult.junitMethodResults
                        .filter { it.type != JUnitMethodResultType.SUCCESS && it.type != JUnitMethodResultType.IGNORED }
                        .map { it.filterStacktrace(assignment.packageName.orEmpty()); it }
                        .joinToString(separator = "\n")
            }
        }

        if (result.isEmpty()) {
            return null
        } else {
            return result
        }

//        if (hasJUnitErrors() == true) {
//            val testReport = File("${mavenizedProjectFolder}/target/surefire-reports")
//                    .walkTopDown()
//                    .filter { it -> it.name.endsWith(".txt") }
//                    .map { it -> String(Files.readAllBytes(it.toPath()))  }
//                    .joinToString(separator = "\n")
//            return testReport
//        }
//        return null
    }

    /**
     * Determines if the student's (own) Test class contains at least the minimum number of JUnit tests that are expected
     * by the [Assignment].
     *
     * @return a String with an informative error message or null.
     */
    fun notEnoughStudentTestsMessage() : String? {

        if (!assignment.acceptsStudentTests) {
            throw IllegalArgumentException("This method shouldn't have been called!")
        }

        val junitSummary = junitSummaryAsObject(TestType.STUDENT)

        if (junitSummary == null) {
            return "The submission doesn't include unit tests. " +
                    "The assignment requires a minimum of ${assignment.minStudentTests} tests."
        }

        if (junitSummary.numTests < assignment.minStudentTests!!) {
            return "The submission only includes ${junitSummary.numTests} unit tests. " +
                    "The assignment requires a minimum of ${assignment.minStudentTests} tests."
        }

        return null
    }

    fun testResults() : List<JUnitMethodResult>? {
        if (assignment.assignmentTestMethods.isEmpty()) {
            return null  // assignment is not properly configured
        }

        var globalMethodResults = mutableListOf<JUnitMethodResult>()
        for (junitResult in junitResults) {
            if (junitResult.isTeacherPublic(assignment) || junitResult.isTeacherHidden()) {
                globalMethodResults.addAll(junitResult.junitMethodResults)
            }
        }

        var result = mutableListOf<JUnitMethodResult>()
        for (assignmentTest in assignment.assignmentTestMethods) {
            var found = false
            for (submissionTest in globalMethodResults) {
                if (submissionTest.methodName.equals(assignmentTest.testMethod) &&
                        submissionTest.getClassName().equals(assignmentTest.testClass)) {
                    result.add(submissionTest)
                    found = true
                    break
                }
            }

            // make sure there are no holes in the tests "matrix"
            if (!found) {
                result.add(JUnitMethodResult.empty())
            }
        }

        return result
    }

    /**
     * Converts an error generated by the Detekt Kotlin static analysis plugin into a more human readbale / friendly
     * message.
     *
     * @param originalError is a String with the Detekt error message
     * 
     * @return a String with the "converted" error message
     */
    private fun translateDetektError(originalError: String) : String {

        // TODO language
        return originalError
                .replace("VariableNaming -", "Nome da variável deve começar por letra minúscula. " +
                        "Caso o nome tenha mais do que uma palavra, as palavras seguintes devem ser capitalizadas (iniciadas por uma maiúscula) -")
                .replace("FunctionNaming -", "Nome da função deve começar por letra minúscula. " +
                        "Caso o nome tenha mais do que uma palavra, as palavras seguintes devem ser capitalizadas (iniciadas por uma maiúscula) -")
                .replace("FunctionParameterNaming -", "Nome do parâmetro de função deve começar por letra minúscula. " +
                        "Caso o nome tenha mais do que uma palavra, as palavras seguintes devem ser capitalizadas (iniciadas por uma maiúscula) -")
                .replace("VariableMinLength -", "Nome da variável demasiado pequeno -")
                .replace("VarCouldBeVal -", "Variável imutável declarada com var -")
                .replace("MandatoryBracesIfStatements -", "Instrução 'if' sem chaveta -")
                .replace("ComplexCondition -", "Condição demasiado complexa -")
                .replace("StringLiteralDuplication -", "String duplicada. Deve ser usada uma constante -")
                .replace("NestedBlockDepth -", "Demasiados níveis de blocos dentro de blocos -")
                .replace("UnsafeCallOnNullableType -", "Não é permitido usar o !! pois pode causar crashes -")
                .replace("MaxLineLength -", "Linha demasiado comprida -")
                .replace("LongMethod -", "Função com demasiadas linhas de código -")
                .replace("ForbiddenKeywords -", "Utilização de instruções proibidas -")
    }
}

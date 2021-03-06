package io.qameta.allure.android

import io.qameta.allure.android.model.Label
import io.qameta.allure.android.model.Status
import io.qameta.allure.android.model.StatusDetails
import io.qameta.allure.android.model.TestResult
import io.qameta.allure.android.model.TestResultContainer
import io.qameta.allure.android.utils.getClassDisplayName
import io.qameta.allure.android.utils.getHistoryId
import io.qameta.allure.android.utils.getLabels
import io.qameta.allure.android.utils.getLinks
import io.qameta.allure.android.utils.getMethodDisplayName
import io.qameta.allure.android.utils.getPackage
import org.junit.Ignore
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * @author b.mukvich on 01.06.2017.
 */
open class AllureRunListener(private val lifecycle: AllureLifecycle = AllureCommonLifecycle) : RunListener() {
    private val testCases = object : InheritableThreadLocal<String>() {
        public override fun initialValue() = UUID.randomUUID().toString()
    }

    private val containers = ConcurrentHashMap<String, TestResultContainer>()

    /**
     * Called before any tests have been run. This may be called on an
     * arbitrary thread.

     * @param description describes the tests to be run
     */
    @Throws(Exception::class)
    override fun testRunStarted(description: Description) {
        // do nothing
    }

    fun testRunStarted() {
        lifecycle.startTestContainer(TestResultContainer(
                uuid = testCases.get(),
                name = "TESTS",
                start = System.currentTimeMillis()))
    }

    /**
     * Called when all tests have finished. This may be called on an
     * arbitrary thread.

     * @param result the summary of the test run, including all the tests that failed
     */
    @Throws(Exception::class)
    override fun testRunFinished(result: Result?) {
        testRunFinished()
    }

    fun testRunFinished() {
        Collections.list(containers.keys()).forEach {
            finalizeContainer(containers.remove(it)?.uuid)
        }
        finalizeContainer(testCases.get())
        testCases.remove()
    }

    /**
     * Called when an atomic test is about to be started.

     * @param description the description of the test that is about to be run
     * *                    (generally a class and method name)
     */
    @Throws(Exception::class)
    override fun testStarted(description: Description) {
        // val uuid = AllureStorage.getTest()
        val testResult = createTestResult(description)
        with(lifecycle) {
            scheduleTestCase(getContainer(description).uuid, testResult)
            startTestCase(testResult.uuid)
        }
    }

    /**
     * Called when an atomic test has finished, whether the test succeeds or fails.

     * @param description the description of the test that just ran
     */
    @Throws(Exception::class)
    override fun testFinished(description: Description) {
        with(lifecycle) {
            updateTestCase {
                if (status == null) status = Status.PASSED
            }
            stopTestCase()
            writeTestCase()
        }
    }

    /**
     * Called when an atomic test fails, or when a listener throws an exception.
     *
     *
     *
     * In the case of a failure of an atomic test, this method will be called
     * with the same `Description` passed to
     * [.testStarted], from the same thread that called
     * [.testStarted].
     *
     *
     *
     * In the case of a listener throwing an exception, this will be called with
     * a `Description` of [Description.TEST_MECHANISM], and may be called
     * on an arbitrary thread.

     * @param failure describes the test that failed and the exception that was thrown
     */
    @Throws(Exception::class)
    override fun testFailure(failure: Failure) {
        testWithException(failure)
    }

    /**
     * Called when an atomic test flags that it assumes a condition that is
     * false

     * @param failure describes the test that failed and the
     * *                [org.junit.AssumptionViolatedException] that was thrown
     */
    @Throws(Exception::class)
    override fun testAssumptionFailure(failure: Failure) {
        testWithException(failure)
    }

    private fun testWithException(failure: Failure) {
        if (failure.description.isTest) {
            val uuid = getUUIDTestResult(failure.description)
            testWithException(uuid, failure)
        } else {
            suiteWithException(failure)
        }
    }

    /**
     * Called when a test will not be run, generally because a test method is annotated
     * with [org.junit.Ignore].

     * @param description describes the test that will not be run
     */
    @Throws(Exception::class)
    override fun testIgnored(description: Description) {
        val testResult = createTestResult(description)
        testResult.status = Status.SKIPPED
        testResult.statusDetails = getIgnoredMessage(description)
        testResult.start = System.currentTimeMillis()

        with(lifecycle) {
            scheduleTestCase(getContainer(description).uuid, testResult)
            startTestCase(testResult.uuid)
            stopTestCase(testResult.uuid)
            writeTestCase(testResult.uuid)
        }
    }

    private fun getIgnoredMessage(description: Description): StatusDetails {
        val ignore = description.getAnnotation(Ignore::class.java)
        val message = if (ignore?.value?.isNotEmpty() == true)
            ignore.value else "Test ignored (without reason)!"

        return StatusDetails(message = message)
    }

    protected open fun finalizeContainer(container: String?) {
        with(lifecycle) {
            stopTestContainer(container)
            writeTestContainer(container)
        }
    }

    protected open fun getContainer(description: Description): TestResultContainer {
        return containers[description.className] ?: createContainer(description)
    }

    protected open fun createContainer(description: Description): TestResultContainer {
        val container = TestResultContainer(
                name = getClassDisplayName(description),
                start = System.currentTimeMillis()
        )

        containers.put(description.className, container)
        lifecycle.startTestContainer(testCases.get(), container)
        return container
    }

    private fun createTestResult(description: Description): TestResult = TestResult(
            uuid = getUUIDTestResult(description),
            historyId = getHistoryId(description),
            name = getMethodDisplayName(description),
            fullName = "${description.className}.${description.methodName}",
            links = getLinks(description),
            labels = listOf(
                    Label("package", getPackage(description.testClass)),
                    Label("testClass", description.className),
                    Label("testMethod", description.methodName),
                    Label("suite", getClassDisplayName(description)))
                    + getLabels(description)
    )


    private fun testWithException(uuid: String, failure: Failure) {
        with(lifecycle) {
            updateTestCase(uuid) {
                status = Status.fromThrowable(failure.exception)
                statusDetails = StatusDetails.fromThrowable(failure.exception)
            }
        }
    }

    private fun suiteWithException(failure: Failure) {
        failure.description.children.forEach {
            val uuid = getUUIDTestResult(it)
            testWithException(uuid, failure)
        }
    }

    private fun getUUIDTestResult(description: Description): String {
        return "${testCases.get()}-${description.className}#${description.methodName}"
    }
}

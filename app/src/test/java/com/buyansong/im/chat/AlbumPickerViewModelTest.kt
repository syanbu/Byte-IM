package com.buyansong.im.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumPickerViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoadsAlbumImagesFromRepository() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(listOf("uri-a", "uri-b"), fixture.viewModel.state.value.images.map { it.uriString })
    }

    @Test
    fun selectingImagesRecordsTapOrder() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.toggleSelection(fixture.image("uri-a"))
        fixture.viewModel.toggleSelection(fixture.image("uri-b"))
        fixture.viewModel.toggleSelection(fixture.image("uri-c"))

        assertEquals(listOf("uri-a", "uri-b", "uri-c"), fixture.viewModel.state.value.selected.map { it.uriString })
        assertEquals(listOf(0, 1, 2), fixture.viewModel.state.value.selected.map { it.selectionOrder })
    }

    @Test
    fun deselectingImageCompactsSelectionOrder() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.toggleSelection(fixture.image("uri-a"))
        fixture.viewModel.toggleSelection(fixture.image("uri-b"))
        fixture.viewModel.toggleSelection(fixture.image("uri-c"))
        fixture.viewModel.toggleSelection(fixture.image("uri-b"))

        assertEquals(listOf("uri-a", "uri-c"), fixture.viewModel.state.value.selected.map { it.uriString })
        assertEquals(listOf(0, 1), fixture.viewModel.state.value.selected.map { it.selectionOrder })
    }

    @Test
    fun selectionIsCappedAtNineImages() = runTest {
        val fixture = Fixture(this)

        (1..10).forEach { index ->
            fixture.viewModel.toggleSelection(fixture.image("uri-$index"))
        }

        assertEquals((1..9).map { "uri-$it" }, fixture.viewModel.state.value.selected.map { it.uriString })
    }

    @Test
    fun removingSelectedImageCompactsSelectionOrder() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.toggleSelection(fixture.image("uri-a"))
        fixture.viewModel.toggleSelection(fixture.image("uri-b"))
        fixture.viewModel.toggleSelection(fixture.image("uri-c"))
        fixture.viewModel.removeSelected("uri-b")

        assertEquals(listOf("uri-a", "uri-c"), fixture.viewModel.state.value.selected.map { it.uriString })
        assertEquals(listOf(0, 1), fixture.viewModel.state.value.selected.map { it.selectionOrder })
    }

    private class Fixture(scope: TestScope) {
        val repository = FakeAlbumImageRepository(
            listOf(
                image("uri-a"),
                image("uri-b")
            )
        )
        val viewModel = AlbumPickerViewModel(
            repository = repository,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        fun image(uriString: String): AlbumImageItem {
            return AlbumImageItem(
                id = uriString.hashCode().toLong(),
                uriString = uriString,
                dateTakenMillis = 1_000L,
                width = 100,
                height = 100
            )
        }
    }

    private class FakeAlbumImageRepository(
        private val images: List<AlbumImageItem>
    ) : AlbumImageRepository {
        override suspend fun loadImages(limit: Int): List<AlbumImageItem> {
            return images.take(limit)
        }
    }
}

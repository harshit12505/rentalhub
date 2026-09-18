package com.rentalhub.service;

import com.rentalhub.dto.PropertyView;
import com.rentalhub.exception.ImageStorageUnavailableException;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.storage.ImageFormat;
import com.rentalhub.storage.ImageStorageSettings;
import com.rentalhub.storage.ImageStore;
import com.rentalhub.storage.ImageStoreException;
import com.rentalhub.storage.UnconfiguredImageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The order of the upload steps, and what happens when one of them fails. The API test proves
 * the happy paths against a real bucket; this one makes each step fail on purpose.
 */
class ListingImageServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final ImageStorageSettings SETTINGS = new ImageStorageSettings(DataSize.ofMegabytes(5), 10,
            new ImageStorageSettings.S3("bucket", "ap-south-1", null, false, null, null));

    private final ImageStore store = mock(ImageStore.class);
    private final ListingImageUpdates updates = mock(ListingImageUpdates.class);
    private final ListingImageService service = new ListingImageService(store, SETTINGS, updates);

    private static MockMultipartFile photo() {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG);
    }

    @Test
    @DisplayName("if the row cannot be written, the file just uploaded is deleted again")
    void compensatesAFailedInsert() {
        when(store.configured()).thenReturn(true);
        when(updates.add(anyLong(), anyLong(), anyString()))
                .thenThrow(new OptimisticLockingFailureException("another upload won"));

        assertThatThrownBy(() -> service.upload(1, 2, photo())).isInstanceOf(OptimisticLockingFailureException.class);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(store).put(stored.capture(), any(), eq(ImageFormat.JPEG));
        verify(store).delete(stored.getValue());
    }

    @Test
    @DisplayName("a store that fails is a 503 with Retry-After, and no row is written")
    void storeFailureIsTemporary() {
        when(store.configured()).thenReturn(true);
        doThrow(new ImageStoreException("connection refused")).when(store).put(anyString(), any(), any());

        assertThatThrownBy(() -> service.upload(1, 2, photo()))
                .isInstanceOfSatisfying(ImageStorageUnavailableException.class,
                        refused -> assertThat(refused.isTemporary()).isTrue());
        verify(updates, never()).add(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("with no storage configured the upload is refused before anything else is looked at")
    void unconfiguredStorageRefusesFirst() {
        ListingImageService unconfigured = new ListingImageService(new UnconfiguredImageStore(), SETTINGS, updates);

        assertThatThrownBy(() -> unconfigured.upload(1, 2, photo()))
                .isInstanceOfSatisfying(ImageStorageUnavailableException.class, refused -> {
                    assertThat(refused.getMessageKey()).isEqualTo("image.storage.notConfigured");
                    assertThat(refused.isTemporary()).as("retrying would never help").isFalse();
                });
        verifyNoInteractions(updates);
    }

    @Test
    @DisplayName("a refusal (not the host, no listing) costs no upload")
    void permissionsAreCheckedBeforeUploading() {
        when(store.configured()).thenReturn(true);
        doThrow(new ResourceNotFoundException("property.notFound", 1L)).when(updates).checkCanAdd(1, 2);

        assertThatThrownBy(() -> service.upload(1, 2, photo())).isInstanceOf(ResourceNotFoundException.class);
        verify(store, never()).put(anyString(), any(), any());
    }

    @Test
    @DisplayName("keys are random and made from the detected type, never from the uploaded file name")
    void keysComeFromTheBytes() {
        when(store.configured()).thenReturn(true);
        when(updates.add(anyLong(), anyLong(), anyString()))
                .thenReturn(new PropertyView.Image(1, "/images/x", 0));
        MockMultipartFile sneaky = new MockMultipartFile("file", "../../etc/passwd.exe", null, JPEG);

        service.upload(7, 2, sneaky);
        service.upload(7, 2, sneaky);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).put(keys.capture(), any(), eq(ImageFormat.JPEG));
        assertThat(keys.getAllValues())
                .allMatch(key -> key.matches("listings/7/[0-9a-f-]{36}\\.jpg"))
                .doesNotHaveDuplicates();
    }
}

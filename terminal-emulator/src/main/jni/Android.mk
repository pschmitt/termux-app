LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_LDFLAGS += -Wl,--build-id=sha1
LOCAL_CFLAGS += -ffile-prefix-map=$(LOCAL_PATH)=. -fdebug-prefix-map=$(LOCAL_PATH)=. -fdebug-compilation-dir=.
LOCAL_CPPFLAGS += -ffile-prefix-map=$(LOCAL_PATH)=. -fdebug-prefix-map=$(LOCAL_PATH)=. -fdebug-compilation-dir=.
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_RRO_THEME := IconShapePepple
LOCAL_PACKAGE_NAME := IconShapePebbleOverlay
LOCAL_SDK_VERSION := current
LOCAL_PRODUCT_MODULE := true

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

include $(BUILD_RRO_PACKAGE)

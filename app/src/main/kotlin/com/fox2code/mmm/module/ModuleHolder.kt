/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.module

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.annotation.StringRes
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.MainApplication.Companion.formatTime
import com.fox2code.mmm.MainApplication.Companion.getPreferences
import com.fox2code.mmm.MainApplication.Companion.isDisableLowQualityModuleFilter
import com.fox2code.mmm.NotificationType
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks.Companion.checkConfigTargetExists
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.repo.RepoModule
import com.fox2code.mmm.utils.IntentHelper.Companion.getPackageOfConfig
import com.fox2code.mmm.utils.io.PropUtils.Companion.isLowQualityModule
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import timber.log.Timber
import java.util.Objects

@Suppress("unused", "KotlinConstantConditions")
class ModuleHolder : Comparable<ModuleHolder?> {
    val moduleId: String
    val notificationType: NotificationType?
    val separator: Type?
    var footerPx: Int
    var onClickListener: View.OnClickListener? = null
    var moduleInfo: LocalModuleInfo? = null
    var repoModule: RepoModule? = null
    var filterLevel = 0

    constructor(moduleId: String) {
        this.moduleId = Objects.requireNonNull(moduleId)
        notificationType = null
        separator = null
        footerPx = -1
    }

    constructor(notificationType: NotificationType) {
        moduleId = ""
        this.notificationType = notificationType
        separator = null
        footerPx = -1
    }

    constructor(separator: Type?) {
        moduleId = ""
        notificationType = null
        this.separator = separator
        footerPx = -1
    }

    @Suppress("unused")
    constructor(footerPx: Int, header: Boolean) {
        moduleId = ""
        notificationType = null
        separator = null
        this.footerPx = footerPx
        filterLevel = if (header) 1 else 0
    }

    val isModuleHolder: Boolean
        get() = notificationType == null && separator == null && footerPx == -1
    val mainModuleInfo: ModuleInfo
        get() = if (repoModule != null && (moduleInfo == null || moduleInfo!!.versionCode < repoModule!!.moduleInfo.versionCode)) repoModule!!.moduleInfo else moduleInfo!!

    var updateZipUrl: String? = null
        get() = if (moduleInfo == null || repoModule != null && moduleInfo!!.updateVersionCode < repoModule!!.moduleInfo.versionCode) repoModule!!.zipUrl else moduleInfo!!.updateZipUrl
            ?: field

    val updateZipRepo: String?
        get() = if (moduleInfo == null || repoModule != null && moduleInfo!!.updateVersionCode < repoModule!!.moduleInfo.versionCode) repoModule!!.repoData.preferenceId else "update_json"
    val updateZipChecksum: String?
        get() = if (moduleInfo == null || repoModule != null && moduleInfo!!.updateVersionCode < repoModule!!.moduleInfo.versionCode) repoModule!!.checksum else moduleInfo!!.updateChecksum
    val mainModuleName: String?
        get() {
            val moduleInfo = mainModuleInfo
            if (moduleInfo.name == null) throw Error("Error for ${type.name} id $moduleId")
            return moduleInfo.name
        }
    val mainModuleNameLowercase: String
        get() = mainModuleName!!.lowercase()
    val mainModuleConfig: String?
        get() {
            if (moduleInfo == null) return null
            var config = moduleInfo!!.config
            if (config == null && repoModule != null) {
                config = repoModule!!.moduleInfo.config
            }
            return config
        }
    val updateTimeText: String
        get() {
            if (repoModule == null) return ""
            val timeStamp = repoModule!!.lastUpdated
            return if (timeStamp <= 0) "" else formatTime(timeStamp)
        }
    val repoName: String?
        get() = if (repoModule == null) "" else repoModule!!.repoName

    fun hasFlag(flag: Int): Boolean {
        return moduleInfo != null && moduleInfo!!.hasFlag(flag)
    }

    val type: Type
        get() = if (footerPx != -1) {
            Type.FOOTER
        } else if (separator != null) {
            Type.SEPARATOR
        } else if (notificationType != null) {
            Type.NOTIFICATION
        } else if (moduleInfo == null && repoModule != null) {
            Type.INSTALLABLE
        } else if (moduleInfo !== null && moduleInfo!!.versionCode < moduleInfo!!.updateVersionCode || repoModule != null && moduleInfo!!.versionCode < repoModule!!.moduleInfo.versionCode) {
            if (MainApplication.forceDebugLogging) Timber.i("Module %s is updateable", moduleId)
            var ignoreUpdate = false
            try {
                if (getPreferences("mmm")?.getStringSet(
                        "pref_background_update_check_excludes", HashSet()
                    )!!.contains(
                            moduleInfo!!.id
                        )
                ) ignoreUpdate = true
            } catch (ignored: Exception) {
            }
            // now, we just had to make it more fucking complicated, didn't we?
            // we now have pref_background_update_check_excludes_version, which is a id:version stringset of versions the user may want to "skip"
            // oh, and because i hate myself, i made ^ at the beginning match that version and newer, and $ at the end match that version and older
            val stringSetT = getPreferences("mmm")?.getStringSet(
                "pref_background_update_check_excludes_version", HashSet()
            )
            var version = ""
            if (MainApplication.forceDebugLogging) Timber.d(stringSetT.toString())
            // unfortunately, stringset.contains() doesn't work for partial matches
            // so we have to iterate through the set
            for (s in stringSetT!!) {
                if (s.startsWith(moduleInfo!!.id)) {
                    version = s
                    if (MainApplication.forceDebugLogging) Timber.d("igV: %s", version)
                    break
                }
            }
            var remoteVersionCode = moduleInfo!!.updateVersionCode.toString()
            if (repoModule != null) {
                remoteVersionCode = repoModule!!.moduleInfo.versionCode.toString()
            }
            if (version.isNotEmpty()) {
                // now, coerce everything into an int
                val remoteVersionCodeInt = remoteVersionCode.toInt()
                val wantsVersion = version.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[1].replace("[^0-9]".toRegex(), "").toInt()
                // now find out if user wants up to and including this version, or this version and newer
                if (MainApplication.forceDebugLogging) Timber.d("igV start with")
                version =
                    version.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
                // this version and newer
                if (version.startsWith("^")) {
                    if (MainApplication.forceDebugLogging) Timber.d("igV: newer")
                    // the wantsversion and newer
                    if (remoteVersionCodeInt >= wantsVersion) {
                        if (MainApplication.forceDebugLogging) Timber.d("igV: skipping")
                        // if it is, we skip it
                        ignoreUpdate = true
                    }
                } else if (version.endsWith("$")) {
                    if (MainApplication.forceDebugLogging) Timber.d("igV: older")
                    // this wantsversion and older
                    if (remoteVersionCodeInt <= wantsVersion) {
                        if (MainApplication.forceDebugLogging) Timber.d("igV: skipping")
                        // if it is, we skip it
                        ignoreUpdate = true
                    }
                } else if (wantsVersion == remoteVersionCodeInt) {
                    if (MainApplication.forceDebugLogging) Timber.d("igV: equal")
                    // if it is, we skip it
                    ignoreUpdate = true
                }
            }
            if (ignoreUpdate) {
                if (MainApplication.forceDebugLogging) Timber.d(
                    "Module %s has update, but is ignored",
                    moduleId
                )
                Type.INSTALLABLE
            } else {
                if (hasUpdate()) {
                    MainApplication.getInstance().modulesHaveUpdates = true
                    if (!MainApplication.getInstance().updateModules.contains(moduleId)) {
                        MainApplication.getInstance().updateModules += moduleId
                        MainApplication.getInstance().updateModuleCount++
                    }
                    if (MainApplication.forceDebugLogging) Timber.d(
                        "modulesHaveUpdates = %s, updateModuleCount = %s",
                        MainApplication.getInstance().modulesHaveUpdates,
                        MainApplication.getInstance().updateModuleCount
                    )
                    Type.UPDATABLE
                } else {
                    Type.INSTALLED
                }
            }
        } else {
            Type.INSTALLED
        }

    fun getCompareType(type: Type?): Type? {
        return separator ?: if (notificationType != null && notificationType.special) {
            Type.SPECIAL_NOTIFICATIONS
        } else {
            type
        }
    }

    fun shouldRemove(): Boolean {
        // if type is not installable or updatable and we have repoModule, we should remove
        if (type !== Type.INSTALLABLE && type !== Type.UPDATABLE && repoModule != null) {
            Timber.d(
                "Removing %s because type is %s and repoModule is not null",
                moduleId,
                type.name
            )
            return true
        }
        // if type is updatable but we don't have an update, remove
        if (type === Type.UPDATABLE && !hasUpdate()) {
            Timber.d("Removing %s because type is %s and has no update", moduleId, type.name)
            return true
        }
        // if type is installed we have an update, remove
        if (type === Type.INSTALLED && repoModule != null && hasUpdate()) {
            Timber.d(
                "Removing %s because type is %s and has update and repoModule is not null",
                moduleId,
                type.name
            )
            return true
        }
        // if type is installed but repomodule is not null, we should remove
        if (type === Type.INSTALLED && repoModule != null) {
            Timber.d(
                "Removing %s because type is %s and repoModule is not null",
                moduleId,
                type.name
            )
            return true
        }
        // if lowqualitymodulefilter is enabled and module is low quality, remove
        if (!isDisableLowQualityModuleFilter) {
            if (repoModule != null && isLowQualityModule(repoModule!!.moduleInfo)) {
                Timber.d("Removing %s because repoModule is not null and is low quality", moduleId)
                return true
            }
            if (moduleInfo != null && isLowQualityModule(moduleInfo!!)) {
                Timber.d("Removing %s because moduleInfo is not null and is low quality", moduleId)
                return true
            }
        }
        // if type is installed but
        return notificationType?.shouldRemove()
            ?: (footerPx == -1 && moduleInfo == null && (repoModule == null || !repoModule!!.repoData.isEnabled))
    }

    fun getButtons(
        context: Context?, buttonTypeList: MutableList<ActionButtonType?>, showcaseMode: Boolean
    ) {
        if (!isModuleHolder) return
        val localModuleInfo = moduleInfo
        // Add warning button if module id begins with a dot - this is a hidden module which could indicate malware
        if (moduleId.startsWith(".") || !moduleId.matches("^[a-zA-Z][a-zA-Z0-9._-]+$".toRegex())) {
            buttonTypeList.add(ActionButtonType.WARNING)
        }
        if (localModuleInfo != null && !showcaseMode) {
            buttonTypeList.add(ActionButtonType.UNINSTALL)
        }
        if (repoModule != null && repoModule!!.notesUrl != null) {
            buttonTypeList.add(ActionButtonType.INFO)
        }
        // in below case, module cannot be in both repo and local if version codes are the same (if same, add online button, otherwise add update button)
        if (repoModule != null || localModuleInfo?.updateZipUrl != null && localModuleInfo.updateVersionCode > localModuleInfo.versionCode) {
            buttonTypeList.add(ActionButtonType.UPDATE_INSTALL)
        }
        val rInfo = localModuleInfo?.remoteModuleInfo
        if (localModuleInfo != null && rInfo != null && rInfo.moduleInfo.versionCode <= localModuleInfo.versionCode || localModuleInfo != null && localModuleInfo.updateVersionCode != Long.MIN_VALUE && localModuleInfo.updateVersionCode <= localModuleInfo.versionCode) {
            buttonTypeList.add(ActionButtonType.REMOTE)
            // set updatezipurl on moduleholder

            if (localModuleInfo.updateZipUrl != null) {
                if (MainApplication.forceDebugLogging) Timber.d(
                    "localModuleInfo: %s",
                    localModuleInfo.updateZipUrl
                )
                updateZipUrl = localModuleInfo.updateZipUrl
            }
            if (repoModule != null) {
                if (MainApplication.forceDebugLogging) Timber.d(
                    "repoModule: %s",
                    repoModule!!.zipUrl
                )
                updateZipUrl = repoModule!!.zipUrl
            }
            // last ditch effort, try to get remoteModuleInfo from localModuleInfo
            if (rInfo != null) {
                if (MainApplication.forceDebugLogging) Timber.d(
                    "remoteModuleInfo: %s",
                    rInfo.zipUrl
                )
                updateZipUrl = rInfo.zipUrl
                moduleInfo?.updateZipUrl = rInfo.zipUrl
            }
        }
        val config = mainModuleConfig
        if (config != null) {
            if (config.startsWith("https://www.androidacy.com/") && hasWebView()) {
                buttonTypeList.add(ActionButtonType.CONFIG)
            } else {
                val pkg = getPackageOfConfig(config)
                try {
                    checkConfigTargetExists(context!!, pkg, config)
                    buttonTypeList.add(ActionButtonType.CONFIG)
                } catch (e: PackageManager.NameNotFoundException) {
                    Timber.w("Config package \"$pkg\" missing for module \"$moduleId\"")
                }
            }
        }
        var moduleInfo: ModuleInfo? = mainModuleInfo
        if (moduleInfo == null) { // Avoid concurrency NPE
            if (localModuleInfo == null) return
            moduleInfo = localModuleInfo
        }
        if (moduleInfo.support != null) {
            buttonTypeList.add(ActionButtonType.SUPPORT)
        }
        if (moduleInfo.donate != null) {
            buttonTypeList.add(ActionButtonType.DONATE)
        }
        if (moduleInfo.safe) {
            buttonTypeList.add(ActionButtonType.SAFE)
        }
    }

    fun hasUpdate(): Boolean {
        if (moduleInfo == null) {
            Timber.w("Module %s has no moduleInfo", moduleId)
            return false
        }
        if (repoModule == null && !MainApplication.getInstance().repoModules.containsKey(moduleId)) {
            if (moduleInfo!!.updateVersionCode > moduleInfo!!.versionCode) {
                Timber.d(
                    "Module %s has update from %s to %s",
                    moduleId,
                    moduleInfo!!.versionCode,
                    moduleInfo!!.updateVersionCode
                )
                return true
            }
        } else if (repoModule != null && repoModule!!.moduleInfo.versionCode > moduleInfo!!.versionCode) {
            Timber.d(
                "Module %s has update from repo from %s to %s",
                moduleId,
                moduleInfo!!.versionCode,
                repoModule!!.moduleInfo.versionCode
            )
            return true
        }
        Timber.d("Module %s has no update", moduleId)
        return false
    }

    override operator fun compareTo(other: ModuleHolder?): Int {
        // Compare depend on type, also allow type spoofing
        val selfTypeReal = type
        val otherTypeReal = other!!.type
        val selfType = getCompareType(selfTypeReal)
        val otherType = other.getCompareType(otherTypeReal)
        val compare = selfType!!.compareTo(otherType!!)
        return if (compare != 0) compare else if (selfTypeReal === otherTypeReal) selfTypeReal.compare(
            this, other
        ) else selfTypeReal.compareTo(otherTypeReal)
    }

    override fun toString(): String {
        return "ModuleHolder{moduleId='$moduleId', notificationType=$notificationType, separator=$separator, footerPx=$footerPx}"
    }

    enum class Type(
        @field:StringRes @param:StringRes val title: Int,
        val hasBackground: Boolean,
        val moduleHolder: Boolean
    ) : Comparator<ModuleHolder?> {
        HEADER(R.string.loading, false, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.separator!!.compareTo(o2.separator!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        SEPARATOR(R.string.loading, false, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.separator!!.compareTo(o2.separator!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        NOTIFICATION(R.string.loading, true, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.notificationType!!.compareTo(o2.notificationType!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        UPDATABLE(R.string.updatable, true, true) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    var cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    if (cmp != 0) return cmp
                    val lastUpdated1 =
                        if (o1.repoModule == null) 0L else o1.repoModule!!.lastUpdated
                    val lastUpdated2 =
                        if (o2.repoModule == null) 0L else o2.repoModule!!.lastUpdated
                    cmp = lastUpdated2.compareTo(lastUpdated1)
                    return if (cmp != 0) cmp else o1.mainModuleName!!.compareTo(o2.mainModuleName!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        INSTALLED(R.string.installed, true, true) {
            // get stacktrace for debugging
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    val cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    return if (cmp != 0) cmp else o1.mainModuleNameLowercase.compareTo(o2.mainModuleNameLowercase)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        SPECIAL_NOTIFICATIONS(R.string.loading, true, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    var cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    if (cmp != 0) return cmp
                    val lastUpdated1 =
                        if (o1.repoModule == null) 0L else o1.repoModule!!.lastUpdated
                    val lastUpdated2 =
                        if (o2.repoModule == null) 0L else o2.repoModule!!.lastUpdated
                    cmp = lastUpdated2.compareTo(lastUpdated1)
                    return if (cmp != 0) cmp else o1.mainModuleName!!.compareTo(o2.mainModuleName!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        INSTALLABLE(
            R.string.online_repo, true, true
        ) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    var cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    if (cmp != 0) return cmp
                    val lastUpdated1 =
                        if (o1.repoModule == null) 0L else o1.repoModule!!.lastUpdated
                    val lastUpdated2 =
                        if (o2.repoModule == null) 0L else o2.repoModule!!.lastUpdated
                    cmp = lastUpdated2.compareTo(lastUpdated1)
                    return if (cmp != 0) cmp else o1.mainModuleName!!.compareTo(o2.mainModuleName!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        FOOTER(R.string.loading, false, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.footerPx.compareTo(o2.footerPx)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        };
    }
}

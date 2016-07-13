/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionProfileTest
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.javaDoc.JavaDocLocalInspection
import com.intellij.openapi.project.ProjectManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.profile.codeInspection.ui.SingleInspectionProfilePanel
import com.intellij.testFramework.LightIdeaTestCase
import com.intellij.testFramework.LightPlatformTestCase
import junit.framework.TestCase

class SingleInspectionProfilePanelTest : LightIdeaTestCase() {
  private val myInspection = JavaDocLocalInspection()

  // see IDEA-85700
  fun testSettingsModification() {
    val project = ProjectManager.getInstance().defaultProject
    val profileManager = ProjectInspectionProfileManager.getInstanceImpl(project)
    val profile = profileManager.getProfile(LightPlatformTestCase.PROFILE) as InspectionProfileImpl
    profile.initInspectionTools(project)

    val model = profile.modifiableModel
    val panel = SingleInspectionProfilePanel(profileManager, LightPlatformTestCase.PROFILE, model, profile)
    panel.isVisible = true
    panel.reset()

    val tool = getInspection(model)
    TestCase.assertEquals("", tool.myAdditionalJavadocTags)
    tool.myAdditionalJavadocTags = "foo"
    model.setModified(true)
    panel.apply()
    TestCase.assertEquals(1, InspectionProfileTest.countInitializedTools(model))

    TestCase.assertEquals("foo", getInspection(profile).myAdditionalJavadocTags)
    panel.disposeUI()
  }

  fun testModifyInstantiatedTool() {
    val project = ProjectManager.getInstance().defaultProject
    val profileManager = ProjectInspectionProfileManager.getInstanceImpl(project)
    val profile = profileManager.getProfile(LightPlatformTestCase.PROFILE) as InspectionProfileImpl
    profile.initInspectionTools(project)

    val originalTool = getInspection(profile)
    originalTool.myAdditionalJavadocTags = "foo"

    val model = profile.modifiableModel

    val panel = SingleInspectionProfilePanel(profileManager, LightPlatformTestCase.PROFILE, model, profile)
    panel.isVisible = true
    panel.reset()
    TestCase.assertEquals(InspectionProfileTest.getInitializedTools(model).toString(), 1,
                          InspectionProfileTest.countInitializedTools(model))

    val copyTool = getInspection(model)
    copyTool.myAdditionalJavadocTags = "bar"

    model.setModified(true)
    panel.apply()
    TestCase.assertEquals(1, InspectionProfileTest.countInitializedTools(model))

    TestCase.assertEquals("bar", getInspection(profile).myAdditionalJavadocTags)
    panel.disposeUI()
  }

  fun testDoNotChangeSettingsOnCancel() {
    val project = ProjectManager.getInstance().defaultProject
    val profileManager = InspectionProjectProfileManager.getInstance(project)
    val profile = profileManager.getProfile(LightPlatformTestCase.PROFILE) as InspectionProfileImpl
    profile.initInspectionTools(project)

    val originalTool = getInspection(profile)
    TestCase.assertEquals("", originalTool.myAdditionalJavadocTags)

    val model = profile.modifiableModel
    val copyTool = getInspection(model)
    copyTool.myAdditionalJavadocTags = "foo"
    // this change IS NOT COMMITTED

    TestCase.assertEquals("", getInspection(profile).myAdditionalJavadocTags)
  }

  private fun getInspection(profile: InspectionProfileImpl): JavaDocLocalInspection {
    val original = (profile.getInspectionTool(myInspection.shortName, LightPlatformTestCase.getProject()) as LocalInspectionToolWrapper?)!!
    return original.tool as JavaDocLocalInspection
  }

  override fun setUp() {
    InspectionProfileImpl.INIT_INSPECTIONS = true
    super.setUp()
  }

  override fun tearDown() {
    InspectionProfileImpl.INIT_INSPECTIONS = false
    super.tearDown()
  }

  override fun configureLocalInspectionTools() = arrayOf(myInspection)
}

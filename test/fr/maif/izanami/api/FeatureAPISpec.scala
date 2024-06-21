package fr.maif.izanami.api

import fr.maif.izanami.api.BaseAPISpec.{TestCondition, TestDateTimePeriod, TestFeature, TestFeatureContext, TestFeaturePatch, TestProject, TestSituationBuilder, TestTenant, TestUser, TestUserListRule, TestWasmConfig, disabledFeatureBase64, enabledFeatureBase64}
import play.api.libs.json.{JsArray, JsBoolean, JsObject, JsString, Json}
import play.api.test.Helpers._

import java.time.{LocalDateTime, OffsetDateTime}

class FeatureAPISpec extends BaseAPISpec {
  "Feature PATCH endpoint" should {
    "allow to modify feature enabling" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false),
                TestFeature("f2", enabled = false),
                TestFeature("f3", enabled = true)
              )
            )
        )
        .build()

      val response = situation.patchFeatures(
        "tenant",
        Seq(
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "f1").get}/enabled",
            value = JsBoolean(true)
          )
        )
      )

      response.status mustBe 204

      val project = situation.fetchProject("tenant", "project").json.get

      ((project \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "f1").get \ "enabled")
        .as[Boolean] mustBe true
    }

    "allow to modify multiple features enabling" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("f1", enabled = false),
                TestFeature("f2", enabled = false),
                TestFeature("f3", enabled = true)
              ),
              TestProject("project2")
                .withFeatures(TestFeature("f4", enabled = false), TestFeature("f5", enabled = true))
            )
        )
        .build()

      val response = situation.patchFeatures(
        "tenant",
        Seq(
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "f1").get}/enabled",
            value = JsBoolean(true)
          ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "f2").get}/enabled",
            value = JsBoolean(false)
          ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "f3").get}/enabled",
            value = JsBoolean(false)
          ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project2", "f4").get}/enabled",
            value = JsBoolean(true)
          ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project2", "f5").get}/enabled",
            value = JsBoolean(false)
          )
        )
      )

      response.status mustBe 204

      val project = situation.fetchProject("tenant", "project").json.get

      ((project \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "f1").get \ "enabled")
        .as[Boolean] mustBe true
      ((project \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "f2").get \ "enabled")
        .as[Boolean] mustBe false
      ((project \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "f3").get \ "enabled")
        .as[Boolean] mustBe false

      val project2 = situation.fetchProject("tenant", "project2").json.get

      ((project2 \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "f4").get \ "enabled")
        .as[Boolean] mustBe true
      ((project2 \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "f5").get \ "enabled")
        .as[Boolean] mustBe false
    }

    "allow to delete features" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant")
          .withProjects(TestProject("project").withFeatureNames("F1", "F2", "F3"))
        ).loggedInWithAdminRights().build()

      val response = situation.patchFeatures(
        "tenant",
        patches = Seq(
          TestFeaturePatch(
            op = "remove",
            path = s"/${situation.findFeatureId("tenant", "project", "F1").get}"
          ),
          TestFeaturePatch(
            op = "remove",
            path = s"/${situation.findFeatureId("tenant", "project", "F3").get}"
          ))
      )

      response.status mustEqual NO_CONTENT

      val fetchResponse = situation.fetchProject("tenant", "project")
      (fetchResponse.json.get \ "features").as[JsArray].value.length mustBe 1
    }

    "allow to transfer features to a chosen project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant")
          .withProjects(TestProject("project").withFeatureNames("F1", "F2", "F3"), TestProject("new-project"))
        ).loggedInWithAdminRights().build()

      val response = situation.patchFeatures(
        "tenant",
        patches = Seq(
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "F1").get}/project",
            value = JsString("new-project")
          ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "F2").get}/project",
            value = JsString("new-project")
          ))
      )

      response.status mustEqual 204

      val fetchResponse = situation.fetchProject("tenant", "new-project")
      (fetchResponse.json.get \ "features").as[JsArray].value.length mustBe 2
    }
    "prevent to transfer features to a is not admin of the tenant and does not have write right on project " in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(TestProject("project").withFeatureNames("F1", "F2", "F3"), TestProject("new-project"))
        )
        .withUsers(
          TestUser("testuser")
            .withTenantReadWriteRight("tenant")
            .withProjectReadRight(project = "project", tenant = "tenant")
        )
        .loggedAs("testuser")
        .build()

      val response = situation.patchFeatures(
        "tenant",
        patches = Seq(
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "F1").get}/project",
            value = JsString("new-project")
          ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "F2").get}/project",
            value = JsString("new-project")
          ))
      )
      response.status mustBe FORBIDDEN
    }
    "allow to applying multiple tags to features " in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project").withFeatures(
                TestFeature("F1", tags = Seq("t1")),
                TestFeature("F2"),
                TestFeature("F3", tags = Seq("t2"))            ),
            )
        )
        .build()
      val response = situation.patchFeatures(
        "tenant",
        Seq(TestFeaturePatch(
          op = "replace",
          path = s"/${situation.findFeatureId("tenant", "project", "F1").get}/tags",
          value = JsArray(Seq(JsString("t1"), JsString("t3"), JsString("t4")))

        ),
          TestFeaturePatch(
            op = "replace",
            path = s"/${situation.findFeatureId("tenant", "project", "F2").get}/tags",
            value = JsArray(Seq(JsString("t2")))

          ))
      )
      response.status mustEqual 204
      val fetchResponse = situation.fetchProject("tenant", "project").json.get
      ((fetchResponse \ "features").as[Seq[JsObject]].find(obj => (obj \ "name").as[String] == "F1").get \ "tags")
        .as[JsArray].value.length mustBe 3
    }

  }

  "Feature POST endpoint" should {
    "allow to create wasm features using wasm manager script" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      val response = situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )
      response.status mustBe CREATED
    }

    "allow to create feature using an existing script" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant").withProjectNames("foo"))
        .loggedInWithAdminRights()
        .build()

      situation.createFeature(
        "feature",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      // FIXME this should be simpler
      val response = situation.createFeature(
        "feature2",
        enabled = true,
        project = "foo",
        tenant = "tenant",
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Local",
            "path" -> "wasmScript"
          )
        )
      )
      response.status mustBe CREATED
    }

    "prevent feature creation if user is not admin of the tenant and does not have write right on project" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("foo")
            .withProjectNames("bar")
        )
        .withUsers(
          TestUser("testuser")
            .withTenantReadWriteRight("foo")
            .withProjectReadRight(project = "bar", tenant = "foo")
        )
        .loggedAs("testuser")
        .build()

      val result = testSituation.createFeature("my-feature", project = "bar", tenant = "foo")
      result.status mustBe FORBIDDEN
    }

    "allow feature creation if user is admin" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("foo")
            .withProjectNames("bar")
        )
        .loggedInWithAdminRights()
        .build()

      val result = testSituation.createFeature("my-feature", project = "bar", tenant = "foo")
      result.status mustBe CREATED
    }

    "allow feature creation if user has write right on project" in {
      val testSituation = TestSituationBuilder()
        .withTenants(
          TestTenant("foo")
            .withProjectNames("bar")
        )
        .withUsers(
          TestUser("testuser")
            .withTenantReadWriteRight("foo")
            .withProjectReadWriteRight(project = "bar", tenant = "foo")
        )
        .loggedAs("testuser")
        .build()

      val result = testSituation.createFeature("my-feature", project = "bar", tenant = "foo")
      result.status mustBe CREATED
    }

    "allow feature creation with spaces in name" in {
      val tenantName                               = "my-tenant"
      val projectName                              = "my-project"
      val testSituation: BaseAPISpec.TestSituation = TestSituationBuilder()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .loggedInWithAdminRights()
        .build()
      val response                                 = testSituation.createFeature(name = "feature name", project = projectName, tenant = tenantName)

      response.status mustBe CREATED
      val feature = response.json.get
      (feature \ "project").as[String] mustEqual projectName
      (feature \ "enabled").as[Boolean] mustBe true
      (feature \ "name").as[String] mustEqual "feature name"
    }

    "allow feature creation in normal format" in {
      val tenantName                               = "my-tenant"
      val projectName                              = "my-project"
      val testSituation: BaseAPISpec.TestSituation = TestSituationBuilder()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .loggedInWithAdminRights()
        .build()
      val response                                 = testSituation.createFeature(name = "feature-name", project = projectName, tenant = tenantName)

      response.status mustBe CREATED
      val feature = response.json.get
      (feature \ "project").as[String] mustEqual projectName
      (feature \ "enabled").as[Boolean] mustBe true
      (feature \ "name").as[String] mustEqual "feature-name"
    }

    "allow 'RELEASE_DATE' feature creation" in {
      val tenantName                               = "my-tenant"
      val projectName                              = "my-project"
      val testSituation: BaseAPISpec.TestSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val response                                 = testSituation.createFeature(
        name = "rdate",
        project = projectName,
        tenant = tenantName,
        conditions = Set(TestCondition(period = TestDateTimePeriod(begin = LocalDateTime.of(1992, 8, 18, 10, 12, 13))))
      )

      response.status mustBe CREATED
      (response.json.get \ "conditions" \\ "begin").head.as[String] mustEqual "1992-08-18T10:12:13Z"
    }

    "prevent feature creation with empty name" in {
      val tenantName    = "my-tenant"
      val projectName   = "my-project"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()

      val response = testSituation.createFeature(name = "", project = projectName, tenant = tenantName, enabled = false)

      response.status mustBe BAD_REQUEST
    }

    "prevent feature creation with incorrect condition" in {
      val tenantName    = "my-tenant"
      val projectName   = "my-project"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val response      = testSituation.createFeatureWithRawConditions(
        name = "incorrect-feature",
        project = projectName,
        tenant = tenantName,
        conditions = """
          |[{}]
          |""".stripMargin
      )

      response.status mustBe BAD_REQUEST
    }

    "allow period feature creation" in {
      val tenantName    = "my-tenant"
      val projectName   = "my-project"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val response      = testSituation.createFeature(
        name = "drangefeat",
        project = projectName,
        tenant = tenantName,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod(
              begin = LocalDateTime.of(2022, 1, 1, 12, 23, 43),
              end = LocalDateTime.of(2023, 1, 1, 12, 23, 43)
            )
          )
        )
      )

      response.status mustBe CREATED
      val json = response.json
      (json.get \ "conditions" \\ "begin").head.as[String] mustEqual "2022-01-01T12:23:43Z"
      (json.get \ "conditions" \\ "end").head.as[String] mustEqual "2023-01-01T12:23:43Z"
    }

    "prevent feature creation if project does not exist" in {
      val testSituation = TestSituationBuilder()
        .withTenantNames("foo")
        .loggedInWithAdminRights()
        .build()
      val response      = testSituation.createFeature(name = "my-feature", project = "my-project", tenant = "foo")

      response.status mustBe NOT_FOUND
    }

    "allow metadata in feature creation" in {
      val tenantName    = "my-tenant"
      val projectName   = "my-project"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()

      val response = testSituation.createFeature(
        "my-feature",
        project = projectName,
        tenant = tenantName,
        metadata = Json.obj(
          "env" -> "test-env"
        )
      )

      response.status mustBe CREATED
      (response.json.get \ "metadata" \ "env").get.as[String] mustEqual "test-env"
    }

    "allow to create feature with tags" in {
      val tenantName    = "my-tenant"
      val projectName   = "my-project"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjectNames(projectName)
            .withTagNames("my-tag")
        )
        .build()

      val featureResponse = testSituation.createFeature(
        "my-feature",
        project = projectName,
        tenant = tenantName,
        enabled = false,
        tags = Seq("my-tag")
      )

      featureResponse.status mustBe CREATED

      (featureResponse.json.get \ "tags").as[Seq[JsString]].map(v => v.value) must contain theSameElementsAs Seq(
        "my-tag"
      )
    }

    "allow to create feature with non existing tags" in {
      val tenantName    = "my-tenant"
      val projectName   = "my-project"
      val testSituation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjectNames(projectName)
        )
        .build()

      val featureResponse = testSituation.createFeature(
        "my-feature",
        project = projectName,
        tenant = tenantName,
        enabled = false,
        tags = Seq("my-tag")
      )

      featureResponse.status mustBe CREATED

      (featureResponse.json.get \ "tags").as[Seq[JsString]].map(v => v.value) must contain theSameElementsAs Seq(
        "my-tag"
      )
    }
  }

  "Feature PUT endpoint" should {
    "allow to update feature project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant")
          .withProjects(
            TestProject("foo").withFeatures(TestFeature("F1")),
            TestProject("bar"))
        ).loggedInWithAdminRights()
        .build();

      val projectResponse = situation.fetchProject("tenant", "foo")
      val jsonFeature = (projectResponse.json.get \ "features" \ 0).as[JsObject]

      val newFeature = jsonFeature ++ Json.obj("project" -> "bar")

      val updateResponse = situation.updateFeature("tenant", (jsonFeature \ "id").as[String], newFeature)

      updateResponse.status mustBe OK

      val originProjectResponse = situation.fetchProject("tenant", "foo")
      val targetProjectResponse = situation.fetchProject("tenant", "bar")

      (originProjectResponse.json.get \ "features").as[JsArray].value mustBe empty
      (targetProjectResponse.json.get \ "features" \ 0 \ "name").as[String] mustEqual "F1"
    }

    "delete associated local overloads when modifying feature project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant")
          .withProjects(
            TestProject("foo").withFeatures(TestFeature("F1", enabled=true)).withContexts(TestFeatureContext("ctx", overloads = Seq(TestFeature("F1", enabled=false)))),
            TestProject("bar"))
        ).loggedInWithAdminRights()
        .build()


      val projectResponse = situation.fetchProject("tenant", "foo")
      val jsonFeature = (projectResponse.json.get \ "features" \ 0).as[JsObject]
      val newFeature = jsonFeature ++ Json.obj("project" -> "bar")
      val updateResponse = situation.updateFeature("tenant", (jsonFeature \ "id").as[String], newFeature)
      updateResponse.status mustBe OK


      val newOldFeature = jsonFeature ++ Json.obj("project" -> "foo")
      val secondUpdateResponse = situation.updateFeature("tenant", (jsonFeature \ "id").as[String], newOldFeature)
      secondUpdateResponse.status mustBe OK

      val res = situation.fetchContexts("tenant", "foo").json.get

      (res \ 0 \ "overloads").as[JsArray].value mustBe empty

    }

    "not delete associated global overloads when modifying feature project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant")
          .withGlobalContext(TestFeatureContext("ctx"))
          .withProjects(
            TestProject("foo").withFeatures(TestFeature("F1", enabled = true)),
            TestProject("bar"))
        ).loggedInWithAdminRights()
        .build()

      situation.changeFeatureStrategyForContext(tenant="tenant", project="foo", contextPath="ctx", feature="F1", enabled=false)


      val projectResponse = situation.fetchProject("tenant", "foo")
      val jsonFeature = (projectResponse.json.get \ "features" \ 0).as[JsObject]
      val newFeature = jsonFeature ++ Json.obj("project" -> "bar")
      val updateResponse = situation.updateFeature("tenant", (jsonFeature \ "id").as[String], newFeature)
      updateResponse.status mustBe OK


      val newOldFeature = jsonFeature ++ Json.obj("project" -> "foo")
      val secondUpdateResponse = situation.updateFeature("tenant", (jsonFeature \ "id").as[String], newOldFeature)
      secondUpdateResponse.status mustBe OK

      val res = situation.fetchContexts("tenant", "foo").json.get

      (res \ 0 \ "overloads").as[JsArray].value must have length 1

    }

    "not delete associated overloads when updating feature without changing its project" in {
      val situation = TestSituationBuilder()
        .withTenants(TestTenant("tenant")
          .withProjects(
            TestProject("foo").withFeatures(TestFeature("F1", enabled = true)).withContexts(TestFeatureContext("ctx", overloads = Seq(TestFeature("F1", enabled = false)))),
          )
        ).loggedInWithAdminRights()
        .build()


      val projectResponse = situation.fetchProject("tenant", "foo")
      val jsonFeature = (projectResponse.json.get \ "features" \ 0).as[JsObject]
      val newFeature = jsonFeature ++ Json.obj("enabled" -> false)
      val updateResponse = situation.updateFeature("tenant", (jsonFeature \ "id").as[String], newFeature)
      updateResponse.status mustBe OK

      val res = situation.fetchContexts("tenant", "foo").json.get

      (res \ 0 \ "overloads").as[JsArray].value must have length 1

    }

    "allow to change NO_STRATEGY feature enabling" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val featureResponse =
        testSituation.createFeature(name = "feature-name", project = projectName, tenant = tenantName, enabled = false)
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"id": "${featureResponse.id.get}", "project": "${projectName}", "name": "feature-name", "enabled": true}"""
        )
      )

      updateResponse.status mustBe OK
      (updateResponse.json.get \ "enabled").get.as[Boolean] mustBe true
    }

    "allow to create new tags while updating feature" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjects(TestProject(projectName)))
        .build()
      val featureResponse =
        testSituation.createFeature(name = "feature-name", project = projectName, tenant = tenantName, enabled = false)
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"id": "${featureResponse.id.get}", "project": "${projectName}", "name": "feature-name", "enabled": false, "tags": ["tag"]}"""
        )
      )

      updateResponse.status mustBe OK
      (testSituation.fetchTags(tenantName).json.get \\ "name").map(v =>
        v.as[String]
      ) must contain theSameElementsAs Seq("tag")

    }

    "allow to change RELEASE_DATE date" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val featureResponse = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = false,
        conditions = Set(TestCondition(period = TestDateTimePeriod(begin = LocalDateTime.of(1992, 9, 19, 1, 12, 1))))
      )
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"id": "${featureResponse.id.get}", "project": "${projectName}", "name": "feature-name", "enabled": false, "conditions": [{"period": {"type": "DATETIME", "begin": "1993-09-19T01:12:01Z"}}]}"""
        )
      )

      updateResponse.status mustBe OK
      (updateResponse.json.get \ "conditions" \\ "begin").head.as[String] mustEqual "1993-09-19T01:12:01Z"
    }

    "allow to change DATE_RANGE dates" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val featureResponse = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = false,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod(
              begin = LocalDateTime.of(1992, 8, 18, 0, 10, 0),
              end = LocalDateTime.of(2100, 8, 18, 0, 10, 0)
            )
          )
        )
      )
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"id": "${featureResponse.id.get}", "project": "${projectName}", "name": "feature-name", "enabled": false, "conditions": [{"period": {"type": "DATETIME", "begin": "1992-08-19T00:10:00Z", "end": "2100-08-19T00:10:00Z"}}]}"""
        )
      )

      updateResponse.status mustBe OK
      val json = updateResponse.json
      (json.get \ "conditions" \\ "begin").head.as[String] mustEqual "1992-08-19T00:10:00Z"
      (json.get \ "conditions" \\ "end").head.as[String] mustEqual "2100-08-19T00:10:00Z"
    }

    "allow to change tags" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withTagNames("my-tag"))
        .build()
      val featureResponse =
        testSituation.createFeature(name = "feature-name", project = projectName, tenant = tenantName, enabled = false)
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"id": "${featureResponse.id.get}", "project": "${projectName}", "name": "feature-name", "enabled": true, "tags": ["my-tag"]}"""
        )
      )

      updateResponse.status mustBe OK
      (updateResponse.json.get \ "tags").as[Seq[JsString]].map(v => v.value) must contain theSameElementsAs Seq(
        "my-tag"
      )
    }

    "allow to update feature with tags without changing tags" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withTagNames("my-tag"))
        .build()
      val featureResponse =
        testSituation.createFeature(
          name = "feature-name",
          project = projectName,
          tenant = tenantName,
          enabled = false,
          tags = Seq("my-tag")
        )
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"name": "feature-name", "enabled": true, "tags": ["my-tag"], "project": "${projectName}"}"""
        )
      )

      updateResponse.status mustBe OK
      (updateResponse.json.get \ "tags").as[Seq[JsString]].map(v => v.value) must contain theSameElementsAs Seq(
        "my-tag"
      )
    }

    "allow to delete tags" in {
      val tenantName      = "my-tenant"
      val projectName     = "my-project"
      val testSituation   = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName).withTagNames("my-tag"))
        .build()
      val featureResponse = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName,
        enabled = false,
        tags = Seq("my-tag")
      )
      val updateResponse  = testSituation.updateFeature(
        tenantName,
        featureResponse.id.get,
        Json.parse(
          s"""{"id": "${featureResponse.id.get}", "project": "${projectName}", "name": "feature-name", "enabled": true, "tags": []}"""
        )
      )

      updateResponse.status mustBe OK
      (updateResponse.json.get \ "tags" \\ "id").map(v => v.as[String]) mustBe Nil
    }
  }

  "Feature DELETE endpoint" should {
    "delete existing feature" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val testSituation  = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(TestTenant(tenantName).withProjectNames(projectName))
        .build()
      val featureRequest = testSituation.createFeature(
        name = "feature-name",
        project = projectName,
        tenant = tenantName
      )
      val deleteResponse = testSituation.deleteFeature(tenantName, featureRequest.id.get)

      deleteResponse.status mustBe NO_CONTENT
    }

    "return 404 for missing feature" in {
      val situation      = TestSituationBuilder().loggedInWithAdminRights().build()
      val deleteResponse = situation.deleteFeature(id = "d398cb04-1476-4b32-ae9b-8bb4d5f9f3a5", tenant = "foo")

      deleteResponse.status mustBe NOT_FOUND
    }

    "prevent feature deletion if user does not have write right on project" in {
      val tenant    = "my-tenant"
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant(tenant)
            .withProjects(TestProject("my-project").withFeatureNames("my-feature"))
        )
        .withUsers(
          TestUser("testu")
            .withTenantReadWriteRight(tenant)
            .withProjectReadRight("my-project", tenant)
        )
        .loggedAs("testu")
        .build()

      val featureToDeleteId = situation.findFeatureId(tenant, "my-project", "my-feature").get
      val deleteResult      = situation.deleteFeature(tenant, featureToDeleteId)

      deleteResult.status mustBe FORBIDDEN
    }
  }

  "Feature test endpoint" should {
    "return active if wasm feature returns true" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val res = situation.testFeature(
        "tenant",
        TestFeature(
          name = "foo",
          enabled = true,
          wasmConfig = TestWasmConfig(
            name = "wasmScript",
            source = Json.obj(
              "kind" -> "Base64",
              "path" -> enabledFeatureBase64
            )
          )
        ),
        OffsetDateTime.now()
      )

      (res.json.get \ "active").as[Boolean] mustBe true

    }

    "return inactive if wasm feature returns false" in {
      val situation = TestSituationBuilder()
        .withTenantNames("tenant")
        .loggedInWithAdminRights()
        .build()

      val res = situation.testFeature(
        "tenant",
        TestFeature(
          name = "foo",
          enabled = true,
          wasmConfig = TestWasmConfig(
            name = "wasmScript",
            source = Json.obj(
              "kind" -> "Base64",
              "path" -> disabledFeatureBase64
            )
          )
        ),
        OffsetDateTime.now()
      )

      (res.json.get \ "active").as[Boolean] mustBe false

    }

    "return active if feature is enabled without condition" in {
      val situation = TestSituationBuilder().withTenantNames("tenant").loggedInWithAdminRights().build()
      val res       = situation.testFeature("tenant", TestFeature(enabled = true, name = "foo"), OffsetDateTime.now())

      (res.json.get \ "active").as[Boolean] mustBe true
    }

    "return active if feature is active for given user" in {
      val situation = TestSituationBuilder().withTenantNames("tenant").loggedInWithAdminRights().build()
      val res       = situation.testFeature(
        "tenant",
        TestFeature(enabled = true, name = "foo")
          .withConditions(TestCondition(rule = TestUserListRule(users = Set("aaa")))),
        OffsetDateTime.now(),
        "aaa"
      )

      (res.json.get \ "active").as[Boolean] mustBe true
    }

    "return inactive if feature is inactive for given user" in {
      val situation = TestSituationBuilder().withTenantNames("tenant").loggedInWithAdminRights().build()
      val res       = situation.testFeature(
        "tenant",
        TestFeature(enabled = true, name = "foo")
          .withConditions(TestCondition(rule = TestUserListRule(users = Set("aaa")))),
        OffsetDateTime.now(),
        "bbb"
      )

      (res.json.get \ "active").as[Boolean] mustBe false
    }

    "return active if feature is active for given date" in {
      val situation = TestSituationBuilder().withTenantNames("tenant").loggedInWithAdminRights().build()
      val res       = situation.testFeature(
        "tenant",
        TestFeature(enabled = true, name = "foo")
          .withConditions(
            TestCondition(period =
              TestDateTimePeriod().beginAt(LocalDateTime.now().minusDays(5)).endAt(LocalDateTime.now().minusDays(2))
            )
          ),
        OffsetDateTime.now().minusDays(3)
      )

      (res.json.get \ "active").as[Boolean] mustBe true
    }

    "return inactive if feature is inactive for given date" in {
      val situation = TestSituationBuilder().withTenantNames("tenant").loggedInWithAdminRights().build()
      val res       = situation.testFeature(
        "tenant",
        TestFeature(enabled = true, name = "foo")
          .withConditions(
            TestCondition(period =
              TestDateTimePeriod().beginAt(LocalDateTime.now().minusDays(5)).endAt(LocalDateTime.now().plusDays(2))
            )
          ),
        OffsetDateTime.now().minusDays(8)
      )

      (res.json.get \ "active").as[Boolean] mustBe false
    }
  }

  "Existing feature test endpoint" should {
    "return feature state for givent context" in {

      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val contextName    = "my-context"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(TestProject(projectName).withContextNames(contextName))
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(tenantName, projectName, contextName, featureName, true)
      val checkResponse  = situation.testExistingFeature(tenantName, featureRequest.id.get, context = contextName)

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return feature state for givent subsubcontext" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withProjects(
              TestProject(projectName).withContexts(
                TestFeatureContext(
                  name = "my-context",
                  subContext = Set(
                    TestFeatureContext(
                      name = "subcontext",
                      subContext = Set(TestFeatureContext(name = "subsubcontext"))
                    )
                  )
                )
              )
            )
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(
        tenantName,
        projectName,
        "my-context/subcontext/subsubcontext",
        featureName,
        true
      )
      val checkResponse  = situation.testExistingFeature(
        tenantName,
        featureRequest.id.get,
        context = "my-context/subcontext/subsubcontext"
      )

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }

    "return feature state for given global context" in {
      val tenantName     = "my-tenant"
      val projectName    = "my-project"
      val featureName    = "feature-name"
      val situation      = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant(tenantName)
            .withGlobalContext(TestFeatureContext("globalcontext"))
            .withProjects(TestProject(projectName))
            .withAllRightsKey("my-key")
        )
        .build()
      val featureRequest = situation.createFeature(
        name = featureName,
        project = projectName,
        tenant = tenantName,
        enabled = false
      )
      situation.changeFeatureStrategyForContext(tenantName, projectName, "globalcontext", featureName, true)
      val checkResponse  = situation.testExistingFeature(tenantName, featureRequest.id.get, context = "globalcontext")

      checkResponse.status mustBe OK
      (checkResponse.json.get \ "active").get.as[Boolean] mustBe true
    }
  }

  "Feature multi test endpoint" should {
    "return all features for given project" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames(
                  "F1",
                  "F2",
                  "F3"
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        projects = Seq("project")
      )

      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F2", "F3")

    }
    "return all features for given projects" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatureNames(
                  "F1",
                  "F2",
                  "F3"
                ),
              TestProject("project2")
                .withFeatureNames(
                  "F21",
                  "F22",
                  "F23"
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        projects = Seq("project", "project2")
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F2", "F3", "F21", "F22", "F23")
    }
    "return all features for given projects filtered by all tags in" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2", "t3")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t1", "t2", "t3")),
                  TestFeature("F3", tags = Seq("t1", "t2"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t1", "t3")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        projects = Seq("project", "project2"),
        allTagsIn = Seq("t1", "t2")
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F2", "F3", "F23")
    }
    "return all features for given projects filtered by no tag in" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2", "t3")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t1", "t2", "t3")),
                  TestFeature("F3", tags = Seq("t3"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t1", "t3")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        projects = Seq("project", "project2"),
        noTagIn = Seq("t1", "t2")
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F21", "F3")
    }
    "return all features for given projects filtered by one tag in" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2", "t3")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t1", "t2", "t3")),
                  TestFeature("F3", tags = Seq("t3"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t2")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        projects = Seq("project", "project2"),
        oneTagIn = Seq("t1", "t3")
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F2", "F3", "F23")
    }
    "return specified features" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1"),
                  TestFeature("F2"),
                  TestFeature("F3")
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22"),
                  TestFeature("F23")
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        features = Seq(("project", "F1"), ("project", "F3"), ("project2", "F21"))
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F3", "F21")
    }
    "not filter specified features with given tags" in {
      val situation = TestSituationBuilder()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature("F1", tags = Seq("t1")),
                  TestFeature("F2", tags = Seq("t2")),
                  TestFeature("F3", tags = Seq("t1", "t2"))
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature("F21"),
                  TestFeature("F22", tags = Seq("t2")),
                  TestFeature("F23", tags = Seq("t1", "t2"))
                )
            )
        )
        .loggedInWithAdminRights()
        .build()

      val response = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        features = Seq(("project", "F1"), ("project", "F3"), ("project2", "F21")),
        noTagIn = Seq("t1", "t2")
      )
      response.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => (obj \ "name").as[String]) must contain theSameElementsAs Seq("F1", "F3", "F21")
    }
    "return correct activation for resulting feature" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().minusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F2",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().plusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F3",
                    wasmConfig = TestWasmConfig(
                      name = "wasmScript",
                      source = Json.obj(
                        "kind" -> "Base64",
                        "path" -> enabledFeatureBase64,
                        "opts" -> Json.obj()
                      )
                    )
                  )
                )
            )
        )
        .build()

      val result = situation.evaluateFeaturesAsLoggedInUser("tenant", projects = Seq("project"))

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", true),
        ("F2", false),
        ("F3", true)
      )
    }
    "return correct activation for resulting feature with overload and context" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().minusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F2",
                    conditions = Set(
                      TestCondition(period =
                        TestDateTimePeriod(
                          begin = LocalDateTime.now().plusDays(1),
                          end = LocalDateTime.now().plusDays(3)
                        )
                      )
                    )
                  ),
                  TestFeature(
                    "F3"
                  )
                )
                .withContexts(TestFeatureContext("context"))
            )
        )
        .build()

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F1",
        enabled = true,
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F2",
        enabled = true,
        conditions = Set(
          TestCondition(period =
            TestDateTimePeriod(
              begin = LocalDateTime.now().minusDays(1),
              end = LocalDateTime.now().plusDays(3)
            )
          )
        )
      )

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "context",
        "F3",
        enabled = false
      )

      val result = situation.evaluateFeaturesAsLoggedInUser("tenant", projects = Seq("project"), context = "context")

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", false),
        ("F2", true),
        ("F3", false)
      )
    }
    "return correct activation for resulting feature with multiple subcontexts" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = false
                  )
                )
            )
            .withGlobalContext(TestFeatureContext("global"))
        )
        .build()

      situation.createContext("tenant", "project", "local", "global")

      val res = situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "global",
        "F1",
        enabled = true,
        wasmConfig = TestWasmConfig(
          name = "wasmScript",
          source = Json.obj(
            "kind" -> "Base64",
            "path" -> disabledFeatureBase64,
            "opts" -> Json.obj()
          )
        )
      )

      situation.changeFeatureStrategyForContext(
        "tenant",
        "project",
        "global/local",
        "F1",
        enabled = true
      )

      val result =
        situation.evaluateFeaturesAsLoggedInUser("tenant", projects = Seq("project"), context = "global/local")

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", true)
      )
    }

    "return correct activation for resulting feature width provided user" in {
      val situation = TestSituationBuilder()
        .loggedInWithAdminRights()
        .withTenants(
          TestTenant("tenant")
            .withTagNames("t1", "t2")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = true,
                    conditions = Set(
                      TestCondition(
                        rule = TestUserListRule(users = Set("foo"))
                      )
                    )
                  ),
                  TestFeature(
                    "F2",
                    enabled = true,
                    conditions = Set(
                      TestCondition(
                        rule = TestUserListRule(users = Set("bar"))
                      )
                    )
                  )
                )
            )
        )
        .build()

      val result = situation.evaluateFeaturesAsLoggedInUser("tenant", projects = Seq("project"), user = "foo")

      result.json.get
        .as[Map[String, JsObject]]
        .values
        .map(obj => ((obj \ "name").as[String], (obj \ "active").as[Boolean])) must contain theSameElementsAs Seq(
        ("F1", true),
        ("F2", false)
      )
    }

    "return an error when user is not allowed for one of the project" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu").withTenantReadRight("tenant").withProjectReadWriteRight("project", "tenant"))
        .loggedAs("testu")
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = true
                  )
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature(
                    "F2",
                    enabled = true
                  )
                )
            )
        )
        .build()

      val result = situation.evaluateFeaturesAsLoggedInUser("tenant", projects = Seq("project", "project2"))
      result.status mustBe FORBIDDEN
    }
    "return an error when user is not allowed for on of the feature's project" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu").withTenantReadRight("tenant").withProjectReadWriteRight("project", "tenant"))
        .loggedAs("testu")
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = true
                  )
                ),
              TestProject("project2")
                .withFeatures(
                  TestFeature(
                    "F2",
                    enabled = true
                  )
                )
            )
        )
        .build()

      val result = situation.evaluateFeaturesAsLoggedInUser(
        "tenant",
        projects = Seq("project"),
        features = Seq(("project2", "F2"))
      )
      result.status mustBe FORBIDDEN
    }
    "return an error when user is not logged in" in {
      val situation = TestSituationBuilder()
        .withUsers(TestUser("testu").withTenantReadRight("tenant").withProjectReadWriteRight("project", "tenant"))
        .withTenants(
          TestTenant("tenant")
            .withProjects(
              TestProject("project")
                .withFeatures(
                  TestFeature(
                    "F1",
                    enabled = true
                  )
                )
            )
        )
        .build()

      val result = situation.evaluateFeaturesAsLoggedInUser("tenant", projects = Seq("project"))
      result.status mustBe UNAUTHORIZED
    }
  }

}

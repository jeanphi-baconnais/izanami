package fr.maif.izanami.api

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.Timeout
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.{Container, WireMockConfiguration}
import com.github.tomakehurst.wiremock.http.{HttpHeaders, Request}
import fr.maif.izanami.api.BaseAPISpec.{cleanUpDB, shouldCleanUpMails, shouldCleanUpWasmServer, ws}
import fr.maif.izanami.utils.{WasmManagerClient, WiremockResponseDefinitionTransformer}
import org.postgresql.util.PSQLException
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json._
import play.api.libs.ws.ahc.{AhcWSClient, StandaloneAhcWSClient}
import play.api.libs.ws.{WSAuthScheme, WSClient, WSCookie, WSResponse}
import play.api.test.Helpers.{await, OK}
import play.api.mvc.MultipartFormData.FilePart
import play.api.test.DefaultAwaitTimeout

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Paths}
import java.sql.DriverManager
import java.time._
import java.time.format.DateTimeFormatter
import java.util.{Objects, TimeZone}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.DurationInt
import scala.util.Try

class BaseAPISpec
    extends PlaySpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with DefaultAwaitTimeout
    with IzanamiServerTest {
  override implicit def defaultAwaitTimeout: Timeout = 30.seconds

  private var mailjetMockServer: WireMockServer                       = null
  private var mailjetExtension: WiremockResponseDefinitionTransformer = null

  private var mailgunMockServer: WireMockServer                       = null
  private var mailgunExtension: WiremockResponseDefinitionTransformer = null

  def mailjetRequests(): Seq[(Request, HttpHeaders)] = mailjetExtension.requests.toSeq

  def mailgunRequests(): Seq[(Request, HttpHeaders)] = mailgunExtension.requests.toSeq

  override def beforeAll(): Unit = {
    mailjetExtension = new WiremockResponseDefinitionTransformer()
    mailjetMockServer = new WireMockServer(WireMockConfiguration.options().extensions(mailjetExtension).port(9998))
    mailjetMockServer.stubFor(
      WireMock.post("/v3.1/send")
    )
    mailjetMockServer.start()

    mailgunExtension = new WiremockResponseDefinitionTransformer()
    mailgunMockServer = new WireMockServer(WireMockConfiguration.options().extensions(mailgunExtension).port(9997))
    mailgunMockServer.stubFor(
      WireMock.post("/baz.com/messages")
    )
    mailgunMockServer.start()
  }

  override def beforeEach(): Unit = {
    var futures: Seq[Future[Any]] = Seq(Future { cleanUpDB() })

    if (shouldCleanUpWasmServer) {
      futures = futures.appended(clearWasmServer())
      shouldCleanUpWasmServer = false
    }

    if (shouldCleanUpMails) {
      futures = futures.appended(ws.url("http://localhost:1080/api/emails").delete())
      mailjetMockServer.stubFor(
        WireMock.post("/v3.1/send")
      )
      mailgunMockServer.stubFor(
        WireMock.post("/baz.com/messages")
      )
      shouldCleanUpMails = false
    }

    await(Future.sequence(futures))
  }

  def clearWasmServer(): Future[Any] = {
    ws.url("http://localhost:5001/api/plugins")
      .withHttpHeaders("Authorization" -> "Basic YWRtaW4tYXBpLWFwaWtleS1pZDphZG1pbi1hcGktYXBpa2V5LXNlY3JldA==")
      .get()
      .map(response => response.json \\ "pluginId")
      .flatMap(values =>
        Future.sequence(
          values
            .map(js => js.as[String])
            .map(pluginId => ws.url(s"http://localhost:5001/api/plugins/${pluginId}").delete())
        )
      )
  }

  override def afterEach(): Unit = {
    if (shouldCleanUpMails) {
      Option(mailjetMockServer).filter(_.isRunning).foreach(_.resetAll())
      Option(mailjetExtension).foreach(_.reset())

      Option(mailgunMockServer).filter(_.isRunning).foreach(_.resetAll())
      Option(mailgunExtension).foreach(_.reset())
    }
  }

  override def afterAll(): Unit = {
    Option(mailjetMockServer).filter(_.isRunning).foreach(_.stop())
    Option(mailgunMockServer).filter(_.isRunning).foreach(_.stop())
  }
}

object BaseAPISpec extends DefaultAwaitTimeout {
  override implicit def defaultAwaitTimeout: Timeout = 30.seconds
  val SCHEMA_TO_KEEP                                 = Set("INFORMATION_SCHEMA", "IZANAMI", "PUBLIC")
  val BASE_URL                                       = "http://localhost:9000/api"
  val ADMIN_BASE_URL                                 = BASE_URL + "/admin"
  implicit val system                                = ActorSystem()
  implicit val materializer                          = Materializer.apply(system)
  implicit val executor                              = system.dispatcher
  val ws: WSClient                                   = new AhcWSClient(StandaloneAhcWSClient())
  val DATE_TIME_FORMATTER                            = DateTimeFormatter.ISO_INSTANT
  val TIME_FORMATTER                                 = DateTimeFormatter.ISO_TIME
  val TEST_SECRET                                    = "supersafesecret"

  var shouldCleanUpWasmServer = true
  var shouldCleanUpMails      = true

  def cleanUpDB(): Unit = {
    classOf[org.postgresql.Driver]
    val con_str = "jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres"
    val conn    = DriverManager.getConnection(con_str)
    try {
      val stm    = conn.createStatement()
      val result = stm.executeQuery("SELECT schema_name FROM information_schema.schemata")

      while (result.next()) {
        val schema = result.getString(1)
        if (schema.equals("izanami")) {
          conn.createStatement().execute("DELETE FROM izanami.tenants CASCADE")
          // TODO factorize user name
          conn.createStatement().execute("DELETE FROM izanami.users WHERE username != 'RESERVED_ADMIN_USER'")
          conn.createStatement().execute("TRUNCATE TABLE izanami.users_tenants_rights CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.invitations CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.sessions CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.pending_imports CASCADE")
          conn.createStatement().execute("TRUNCATE TABLE izanami.key_tenant CASCADE")
          conn.createStatement().execute("UPDATE izanami.mailers SET configuration='{}'::JSONB")
          conn
            .createStatement()
            .execute("UPDATE izanami.configuration SET mailer='CONSOLE', invitation_mode='RESPONSE'")
        }
        if (!SCHEMA_TO_KEEP.contains(schema.toUpperCase())) {
          try {
            val result = conn.createStatement().executeQuery(s"""DROP SCHEMA "${schema}" CASCADE""")
          } catch {
            case e: PSQLException => // NOT THE RIGHT TO DELETE THIS SCHEMA
            case e: Throwable     => throw e
          }
        }
      }

    } finally {
      conn.close()
    }
  }

  def enabledFeatureBase64  = scala.io.Source.fromResource("enabled_script_feature_base64").getLines().mkString("")
  def disabledFeatureBase64 = scala.io.Source.fromResource("disabled_script_feature_base64").getLines().mkString("")

  def getProjectsForTenant(tenant: String): (Option[JsValue], Int) = {

    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects").get())

    (
      response.status match {
        case OK => Some(response.json)
        case _  => Option.empty
      },
      response.status
    )
  }

  def createFeature(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    createFeatureWithRawConditions(
      name,
      project,
      tenant,
      enabled,
      tags,
      metadata,
      Json.toJson(conditions.map(c => c.json)).toString(),
      wasmConfig,
      id,
      cookies
    )
  }

  def createFeatureAsync(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    createFeatureWithRawConditionsAsync(
      name,
      project,
      tenant,
      enabled,
      tags,
      metadata,
      Json.toJson(conditions.map(c => c.json)).toString(),
      wasmConfig,
      id,
      cookies
    )
  }

  def createFeatureWithRawConditions(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: String,
      wasmConfig: TestWasmConfig = null,
      id: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(
      createFeatureWithRawConditionsAsync(
        name,
        project,
        tenant,
        enabled,
        tags,
        metadata,
        conditions,
        wasmConfig,
        id,
        cookies
      )
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createFeatureWithRawConditionsAsync(
      name: String,
      project: String,
      tenant: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: String,
      wasmConfig: TestWasmConfig = null,
      id: String,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/features")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{
           |"name": "${name}",
           |"tags": [${tags.map(name => s""""${name}"""").mkString(",")}],
           |"metadata": ${Json.stringify(metadata)},
           |"enabled": ${enabled},
           |"conditions": ${Json.parse(conditions).as[JsArray]}
           |${if (Objects.nonNull(wasmConfig))
        s""" ,"wasmConfig": ${Json.stringify(wasmConfig.json)} """
      else ""}
           |${if (Objects.nonNull(id)) s""" ,"id": "$id" """ else s""}
           |}""".stripMargin))
  }

  def createTag(
      name: String,
      tenant: String,
      description: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createTagAsync(name, tenant, description, cookies))
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createTagAsync(
      name: String,
      tenant: String,
      description: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}", "tenant": "${tenant}", "description": "${description}" }"""))
  }

  def createTenantAndProject(tenant: String, project: String): (String, String) = {
    val tenantResult  = createTenant(tenant)
    val projectResult = createProject(project, tenant = tenant)
    (tenantResult.id.get, projectResult.id.get)
  }

  def createTenant(name: String, description: String = null, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(createTenantAsync(name, description, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createTenantAsync(
      name: String,
      description: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}" ${if (description != null) s""", "description": "${description}" """
      else ""} }"""))
  }

  def createProject(
      name: String,
      tenant: String,
      description: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createProjectAsync(name, tenant, description, cookies))
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createProjectAsync(
      name: String,
      tenant: String,
      description: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}", "tenant": "${tenant}" ${if (description != null)
        s""" ,"description": "${description}" """
      else ""} }"""))
  }

  def fetchTenants(right: String = null, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants${if (Objects.nonNull(right)) s"?right=${right}" else ""}")
        .withCookies(cookies: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchTenant(id: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${id}").withCookies(cookies: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchTag(tenant: String, name: String, cookie: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags/${name}").withCookies(cookie: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchTags(tenant: String, cookie: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags").withCookies(cookie: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchProject(tenant: String, projectId: String, cookies: Seq[WSCookie]): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${projectId}")
        .withCookies(cookies: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def fetchProjects(tenant: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects").withCookies(cookies: _*).get())
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def updateFeature(tenant: String, id: String, json: JsValue, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features/${id}").withCookies(cookies: _*).put(json)
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def checkFeature(
      id: String,
      user: String = null,
      headers: Map[String, String] = Map(),
      context: String = null,
      conditions: Boolean = false
  ): RequestResult = {
    val maybeContext = Option(context).map(ctx => s"context=${context}")
    val maybeUser    = Option(user).map(u => s"user=${u}")
    val params       = Seq(maybeContext, maybeUser).filter(_.isDefined).map(_.get).mkString("&")
    val response     = await(
      ws.url(
        s"""${BASE_URL}/v2/features/${id}?conditions=${conditions}${if (!params.isBlank) s"&${params}" else ""}"""
      ).withHttpHeaders(headers.toList: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def deleteFeature(tenant: String, id: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features/${id}")
        .withCookies(cookies: _*)
        .delete()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def checkFeatures(
      projects: Seq[String],
      headers: Map[String, String] = Map(),
      oneTagIn: Seq[String] = Seq(),
      allTagsIn: Seq[String] = Seq(),
      noTagIn: Seq[String] = Seq(),
      user: String = null,
      contextPath: String = null,
      features: Seq[String] = Seq(),
      conditions: Boolean = false
  ): RequestResult = {
    val response = await(
      ws.url(s"""${BASE_URL}/v2/features?conditions=${conditions}&oneTagIn=${oneTagIn
        .mkString(
          ","
        )}&allTagsIn=${allTagsIn.mkString(",")}&noTagIn=${noTagIn.mkString(",")}&projects=${projects.mkString(
        ","
      )}&features=${features.mkString(",")}${Option(user).map(u => s"&user=${u}").getOrElse("")}${if (
        contextPath != null
      ) s"&context=${contextPath}"
      else ""}""")
        .withHttpHeaders(headers.toList: _*)
        .get()
    )
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def createAPIKey(
      tenant: String,
      name: String,
      projects: Seq[String] = Seq(),
      description: String = "",
      enabled: Boolean = true,
      admin: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createAPIKeyAsync(tenant, name, projects, description, enabled, admin, cookies))
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createAPIKeyAsync(
      tenant: String,
      name: String,
      projects: Seq[String] = Seq(),
      description: String = "",
      enabled: Boolean = true,
      admin: Boolean = false,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {

    ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys")
      .withCookies(cookies: _*)
      .post(Json.parse(s"""{ "name": "${name}", "enabled": ${enabled},"admin": ${admin}, "projects": ${toJsArray(
        projects
      )}, "description": "${description}" }"""))
  }

  def toJsArray(elems: Seq[String]): String = {
    s"""[${elems.map(p => s""""${p}"""").mkString(",")}]"""
  }

  def fetchAPIKeys(tenant: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys").withCookies(cookies: _*).get()
    )
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def deleteAPIKey(tenant: String, name: String, cookies: Seq[WSCookie] = Seq()): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys/${name}").withCookies(cookies: _*).delete()
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateAPIKey(
      tenant: String,
      currentName: String,
      newName: String = null,
      description: String,
      projects: Seq[String],
      enabled: Boolean = true,
      admin: Boolean,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/keys/${currentName}")
        .withCookies(cookies: _*)
        .put(Json.parse(s"""
          |{
          | "name": "${if (newName != null) newName else currentName}",
          | "description": "${description}",
          | "enabled": ${enabled},
          | "admin": ${admin},
          | "projects": [${projects.map(p => s""" "${p}" """).mkString(",")}]
          |}
          |""".stripMargin))
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateConfiguration(
      mailProvider: String = "Console",
      invitationMode: String = "Response",
      originEmail: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(updateConfigurationAsync(mailProvider, invitationMode, originEmail, cookies))
    RequestResult(json = Try { response.json }, status = response.status)
  }

  def updateConfigurationAsync(
      mailProvider: String = "Console",
      invitationMode: String = "Response",
      originEmail: String = null,
      cookies: Seq[WSCookie] = Seq(),
      anonymousReporting: Boolean = false,
      anonymousReportingDate: LocalDateTime = LocalDateTime.now()
  ): Future[WSResponse] = {
    val dateStr = anonymousReportingDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    ws.url(s"""${ADMIN_BASE_URL}/configuration""")
      .withCookies(cookies: _*)
      .put(
        if (Objects.isNull(originEmail)) {
          Json.obj(
            "mailer"                 -> mailProvider,
            "invitationMode"         -> invitationMode,
            "anonymousReporting"     -> anonymousReporting,
            "anonymousReportingDate" -> dateStr
          )
        } else {
          Json.obj(
            "mailer"                 -> mailProvider,
            "invitationMode"         -> invitationMode,
            "originEmail"            -> originEmail,
            "anonymousReporting"     -> anonymousReporting,
            "anonymousReportingDate" -> dateStr
          )
        }
      )
  }

  def createContextHierarchy(
      tenant: String,
      project: String,
      context: TestFeatureContext,
      parents: Seq[String] = Seq(),
      cookies: Seq[WSCookie] = Seq()
  ): Unit = {
    await(createContextHierarchyAsync(tenant, project, context, parents, cookies))
  }

  def createContextHierarchyAsync(
      tenant: String,
      project: String,
      context: TestFeatureContext,
      parents: Seq[String] = Seq(),
      cookies: Seq[WSCookie] = Seq()
  ): Future[Unit] = {
    createContextAsync(tenant, project, name = context.name, parents = parents.mkString("/"), cookies = cookies)
      .map(res => {
        if (res.status >= 400) {
          throw new RuntimeException("Failed to create context")
        } else ()
      })
      .flatMap(_ => {
        Future.sequence(
          context.subContext.map(child =>
            createContextHierarchyAsync(tenant, project, child, parents.appended(context.name), cookies)
          )
        )
      })
      .flatMap(_ => {
        Future.sequence(context.overloads.map {
          case TestFeature(name, enabled, _, _, conditions, wasmConfig, _) => {
            changeFeatureStrategyForContextAsync(
              tenant,
              project,
              parents.appended(context.name).mkString("/"),
              name,
              enabled,
              conditions,
              wasmConfig,
              cookies
            ).map(result => {
              if (result.status >= 400) {
                throw new RuntimeException("Failed to create feature overload")
              }
            })
          }
        })
      })
      .map(_ => ())
  }

  def createGlobalContextHierarchyAsync(
      tenant: String,
      context: TestFeatureContext,
      parents: Seq[String] = Seq(),
      cookies: Seq[WSCookie] = Seq()
  ): Future[Unit] = {
    createGlobalContextAsync(tenant, name = context.name, parents = parents.mkString("/"), cookies = cookies)
      .map(result => {
        if (result.status >= 400) {
          throw new RuntimeException("Failed to create context")
        } else ()
      })
      .flatMap(_ => {
        Future.sequence(
          context.subContext.map(child =>
            createGlobalContextHierarchyAsync(tenant, child, parents.appended(context.name), cookies)
          )
        )
      })
      .map(s => ())
  }

  def createContext(
      tenant: String,
      project: String,
      name: String,
      parents: String = "",
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(BaseAPISpec.createContextAsync(tenant, project, name, parents, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createContextAsync(
      tenant: String,
      project: String,
      name: String,
      parents: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts${if (parents.nonEmpty) s"/${parents}"
    else ""}""")
      .withCookies(cookies: _*)
      .post(
        Json.parse(s"""
             |{
             | "name": "${name}"
             |}
             |""".stripMargin)
      )
  }

  def createGlobalContext(
      tenant: String,
      name: String,
      parents: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(createGlobalContextAsync(tenant, name, parents, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def createGlobalContextAsync(
      tenant: String,
      name: String,
      parents: String = "",
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/contexts${if (parents.nonEmpty) s"/${parents}"
    else ""}""")
      .withCookies(cookies: _*)
      .post(
        Json.parse(s"""
               |{
               | "name": "${name}"
               |}
               |""".stripMargin)
      )
  }

  def fetchContexts(tenant: String, project: String, cookies: Seq[WSCookie] = Seq()) = {
    val response = await(
      ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts").withCookies(cookies: _*).get()
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def testFeature(
      tenant: String,
      feature: TestFeature,
      date: OffsetDateTime = OffsetDateTime.now(),
      user: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(
      ws.url(s"""${ADMIN_BASE_URL}/tenants/$tenant/test?date=${DateTimeFormatter.ISO_INSTANT.format(date)}${if (
        user != null
      )
        s"&user=${user}"
      else ""}""")
        .withCookies(cookies: _*)
        .post(Json.parse(s"""{
            |"name": "${feature.name}",
            |"tags": [${feature.tags.map(name => s""""${name}"""").mkString(",")}],
            |"metadata": ${Json.stringify(feature.metadata)},
            |"enabled": ${feature.enabled},
            |"conditions": ${Json.toJson(feature.conditions.map(c => c.json))}
            |${if (Objects.nonNull(feature.wasmConfig)) s""" ,"wasmConfig": ${feature.wasmConfig.json} """ else ""}
            |}""".stripMargin))
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def login(user: String, password: String, rights: Boolean = false): RequestResult = {
    val response = await(
      ws.url(s"""${ADMIN_BASE_URL}/login?rights=${rights}""")
        .withAuth(user, password, WSAuthScheme.BASIC)
        .post("")
    )
    val jsonTry  = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, cookies = response.cookies.toSeq)
  }

  def createUser(
      user: String,
      password: String,
      admin: Boolean = false,
      rights: TestRights = null,
      email: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    await(createUserAsync(user, password, admin, rights, email, cookies))
  }

  def createUserAsync(
      user: String,
      password: String,
      admin: Boolean = false,
      rights: TestRights = null,
      email: String = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[RequestResult] = {
    val realEmail = Option(email).getOrElse(s"${user}@imaginarymail.frfrfezfezrf")
    sendInvitationAsync(realEmail, admin, rights, cookies).flatMap(result => {
      val url   = (result.json \ "invitationUrl").as[String]
      val token = url.split("token=")(1)
      createUserWithTokenAsync(user, password, token).map(response => {
        val jsonTry = Try {
          response.json
        }
        RequestResult(json = jsonTry, status = response.status)
      })
    })
  }

  def createWasmScript(name: String, enabled: Boolean = false): Future[Any] = {
    // FIXME pass name when it will be easy to parametrized
    val futureCreationResponse = ws
      .url(s"http://localhost:5001/api/plugins")
      .withHttpHeaders(("Content-Type", "application/json"))
      .post(
        Json.obj(
          "plugin" -> name,
          "type"   -> "ts"
        )
      )

    futureCreationResponse
      .map(resp => (resp.json \\ "pluginId").head.as[String])
      .flatMap(id => {
        val fileBytes = Files.readAllBytes(
          Paths.get(if (enabled) "test/resources/izanami-enabled.zip" else "test/resources/izanami-disabled.zip")
        )
        ws.url(s"http://localhost:5001/api/plugins/${id}")
          .withHttpHeaders(("Content-Type", "application/octet-stream"))
          .put(fileBytes)
          .flatMap(_ =>
            ws.url(s"http://localhost:5001/api/plugins/${id}/build&plugin_type=js")
              .withHttpHeaders(("Content-Type", "application/octet-stream"))
              .post(fileBytes)
          )
      })
  }

  def createUserWithToken(
      user: String,
      password: String,
      token: String
  ): RequestResult = {
    val response = await(createUserWithTokenAsync(user, password, token))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def createUserWithTokenAsync(
      user: String,
      password: String,
      token: String
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/users""")
      .post(
        Json.obj(
          "username" -> user,
          "password" -> password,
          "token"    -> token
        )
      )
  }

  def sendInvitation(
      email: String,
      admin: Boolean = false,
      rights: TestRights = null,
      cookies: Seq[WSCookie] = Seq()
  ): RequestResult = {
    val response = await(sendInvitationAsync(email, admin, rights, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def sendInvitationAsync(
      email: String,
      admin: Boolean = false,
      rights: TestRights = null,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    val jsonRights = Option(rights).map(r => r.json).getOrElse(Json.obj())
    ws.url(s"""${ADMIN_BASE_URL}/invitation""")
      .withCookies(cookies: _*)
      .post(
        Json.obj(
          "email"  -> email,
          "admin"  -> admin,
          "rights" -> jsonRights
        )
      )
  }

  def changeFeatureStrategyForContext(
      tenant: String,
      project: String,
      contextPath: String,
      feature: String,
      enabled: Boolean,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(
      changeFeatureStrategyForContextAsync(
        tenant,
        project,
        contextPath,
        feature,
        enabled,
        conditions,
        wasmConfig,
        cookies
      )
    )

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status, idField = "name")
  }

  def changeFeatureStrategyForContextAsync(
      tenant: String,
      project: String,
      contextPath: String,
      feature: String,
      enabled: Boolean,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      cookies: Seq[WSCookie] = Seq()
  ) = {
    ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts/${contextPath}/features/${feature}""")
      .withCookies(cookies: _*)
      .put(
        Json.parse(s"""
               |{
               | "enabled": ${enabled},
               | "conditions": [${conditions.map(c => c.json).mkString(",")}]
               | ${if (Objects.nonNull(wasmConfig)) s""" ,"wasmConfig": ${Json.stringify(wasmConfig.json)}"""
        else ""}
               |}
               |""".stripMargin)
      )
  }

  def updateMailerConfiguration(
      mailer: String,
      configuration: JsObject,
      cookies: Seq[WSCookie] = Seq()
  ) = {
    val response = await(updateMailerConfigurationAsync(mailer, configuration, cookies))

    val jsonTry = Try {
      response.json
    }
    RequestResult(json = jsonTry, status = response.status)
  }

  def updateMailerConfigurationAsync(
      mailer: String,
      configuration: JsObject,
      cookies: Seq[WSCookie] = Seq()
  ): Future[WSResponse] = {
    ws.url(s"""${ADMIN_BASE_URL}/configuration/mailer/${mailer}""")
      .withCookies(cookies: _*)
      .put(configuration)
  }

  sealed trait TestPeriod {
    def json: JsObject
  }

  sealed trait TestRule {
    def json: JsObject
  }

  case class RequestResult(
      json: Try[JsValue],
      status: Integer,
      idField: String = "id",
      cookies: Seq[WSCookie] = Seq()
  ) {
    def id: Try[String] = json.map(json => (json \ idField).as[String])
  }

  case class TestProjectRight(name: String, level: String = "Read") {
    def json: JsObject = Json.obj(
      "level" -> level
    )
  }

  case class TestKeyRight(name: String, level: String = "Read") {
    def json: JsObject = Json.obj(
      "level" -> level
    )
  }

  case class TestTenantRight(
      name: String,
      level: String = "Read",
      projects: Map[String, TestProjectRight] = Map(),
      keys: Map[String, TestKeyRight] = Map()
  ) {
    def json: JsObject = Json.obj(
      "level"    -> level,
      "projects" -> projects.view.mapValues(_.json),
      "keys"     -> keys.view.mapValues(_.json)
    )

    def addProjectRight(project: String, level: String): TestTenantRight = {
      copy(projects = projects + (project -> TestProjectRight(project, level)))
    }

    def addKeyRight(key: String, level: String): TestTenantRight = {
      copy(keys = keys + (key -> TestKeyRight(key, level)))
    }
  }

  case class TestRights(tenants: Map[String, TestTenantRight] = Map()) {
    def addTenantRight(name: String, level: String) = copy(tenants + (name -> TestTenantRight(name, level)))
    def addProjectRight(project: String, tenant: String, level: String) = {
      if (!tenants.contains(tenant)) {
        throw new RuntimeException("Tenant does not exist")
      }
      val newProjects = tenants(tenant).projects + (project -> TestProjectRight(name = project, level = level))

      copy(tenants + (tenant -> tenants(tenant).copy(projects = newProjects)))
    }

    def addKeyRight(key: String, tenant: String, level: String) = {
      if (!tenants.contains(tenant)) {
        throw new RuntimeException("Tenant does not exist")
      }
      val newKeys = tenants(tenant).keys + (key -> TestKeyRight(name = key, level = level))

      copy(tenants + (tenant -> tenants(tenant).copy(keys = newKeys)))
    }

    def json: JsObject = Json.obj(
      "tenants" -> tenants.view.mapValues(_.json)
    )
  }

  case class TestFeature(
      name: String,
      enabled: Boolean = true,
      tags: Seq[String] = Seq(),
      metadata: JsObject = JsObject.empty,
      conditions: Set[TestCondition] = Set(),
      wasmConfig: TestWasmConfig = null,
      id: String = null
  ) {
    def withConditions(testCondition: TestCondition*): TestFeature = {
      copy(conditions = this.conditions ++ testCondition)
    }
  }

  case class TestWasmConfig(
      name: String,
      source: JsObject,
      memoryPages: Int = 100,
      config: Map[String, String] = Map.empty,
      wasi: Boolean = true,
      opa: Boolean = false
  ) {
    def json: JsObject = Json.obj(
      "name"        -> name,
      "source"      -> source,
      "memoryPages" -> memoryPages,
      "config"      -> config,
      "wasi"        -> wasi,
      "opa"         -> opa /*,
      "kill_options" -> Json.obj(
        "immortal" -> true
      )*/
    )
  }

  case class TestCondition(
      rule: TestRule = null,
      period: TestPeriod = null
  ) {
    def json: JsObject = Json.obj(
      "rule"   -> Option(rule).map(r => r.json),
      "period" -> Option(period).map(p => p.json)
    )
  }

  case class TestDateTimePeriod(
      begin: LocalDateTime = null,
      end: LocalDateTime = null,
      hourPeriods: Seq[TestHourPeriod] = Seq(),
      days: TestDayPeriod = null,
      timezone: ZoneId = TimeZone.getDefault().toZoneId
  ) extends TestPeriod {
    def json: JsObject = {
      Json.obj(
        "type"           -> "DATETIME",
        "begin"          -> Option(begin).map(begin => begin.atOffset(ZoneOffset.UTC).format(DATE_TIME_FORMATTER)),
        "end"            -> Option(end).map(end => end.atOffset(ZoneOffset.UTC).format(DATE_TIME_FORMATTER)),
        "hourPeriods"    -> hourPeriods.map(p => p.json),
        "timezone"       -> timezone.toString,
        "activationDays" -> Option(days).map(d =>
          Json.obj(
            "days" -> d.days.map(_.name)
          )
        )
      )
    }

    def beginAt(begin: LocalDateTime): TestDateTimePeriod   = copy(begin = begin)
    def endAt(end: LocalDateTime): TestDateTimePeriod       = copy(end = end)
    def atHours(hours: TestHourPeriod*): TestDateTimePeriod = copy(hourPeriods = this.hourPeriods ++ hours)
    def atDays(days: DayOfWeek*): TestDateTimePeriod        = copy(days = TestDayPeriod(days = days.toSet))
  }

  case class TestHourPeriod(startTime: LocalTime = null, endTime: LocalTime = null) {
    def json: JsObject = {
      Json.obj(
        "startTime" -> startTime
          .atOffset(OffsetDateTime.now().getOffset)
          .format(DateTimeFormatter.ISO_TIME),
        "endTime"   -> endTime
          .atOffset(OffsetDateTime.now().getOffset)
          .format(DateTimeFormatter.ISO_TIME)
      )
    }
  }

  case class TestDayPeriod(days: Set[DayOfWeek])

  case class TestPercentageRule(percentage: Int)  extends TestRule {
    override def json: JsObject = Json.obj(
      "type"       -> "PERCENTAGE",
      "percentage" -> percentage
    )
  }
  case class TestUserListRule(users: Set[String]) extends TestRule {
    override def json: JsObject = Json.obj(
      "type"  -> "USER_LIST",
      "users" -> users
    )
  }

  case class TestApiKey(
      name: String,
      projects: Seq[String] = Seq(),
      description: String = "",
      enabled: Boolean = true,
      admin: Boolean = false
  )

  case class TestFeatureContext(
      name: String,
      subContext: Set[TestFeatureContext] = Set(),
      overloads: Seq[TestFeature] = Seq()
  ) {

    def withSubContexts(contexts: TestFeatureContext*): TestFeatureContext = {
      copy(subContext = this.subContext ++ contexts)
    }

    def withSubContextNames(contexts: String*): TestFeatureContext = {
      copy(subContext = this.subContext ++ (contexts.map(n => TestFeatureContext(name = n))))
    }

    def withFeatureOverload(
        feature: TestFeature
    ): TestFeatureContext = {
      copy(overloads = overloads.appended(feature))
    }
  }

  case class TestTenant(
      name: String,
      description: String = "",
      projects: Set[TestProject] = Set(),
      tags: Set[TestTag] = Set(),
      apiKeys: Set[TestApiKey] = Set(),
      allRightKeys: Set[String] = Set(),
      contexts: Set[TestFeatureContext] = Set()
  ) {
    def withProjects(projects: TestProject*): TestTenant = {
      copy(projects = this.projects ++ projects)
    }

    def withProjectNames(projects: String*): TestTenant = {
      copy(projects = this.projects ++ projects.map(name => TestProject(name = name)))
    }

    def withTags(tags: TestTag*): TestTenant = {
      copy(tags = this.tags ++ tags)
    }

    def withTagNames(tags: String*): TestTenant = {
      copy(tags = this.tags ++ tags.map(t => TestTag(name = t)))
    }

    def withApiKeys(apiKeys: TestApiKey*): TestTenant = {
      copy(apiKeys = this.apiKeys ++ apiKeys)
    }

    def withAllRightsKey(name: String): TestTenant = {
      copy(allRightKeys = this.allRightKeys + name)
    }

    def withApiKeyNames(apiKeys: String*): TestTenant = {
      copy(apiKeys = this.apiKeys ++ apiKeys.map(n => TestApiKey(name = n)))
    }

    def withGlobalContext(contexts: TestFeatureContext*): TestTenant = {
      copy(contexts = this.contexts ++ contexts)
    }
  }
  case class TestProject(
      name: String,
      description: String = "",
      features: Set[TestFeature] = Set(),
      contexts: Set[TestFeatureContext] = Set()
  ) {
    def withFeatures(features: TestFeature*): TestProject = {
      copy(features = this.features ++ features)
    }

    def withFeatureNames(features: String*): TestProject = {
      copy(features = this.features ++ features.map(f => new TestFeature(name = f)))
    }

    def withContexts(contexts: TestFeatureContext*): TestProject = {
      copy(contexts = this.contexts ++ contexts)
    }

    def withContextNames(contexts: String*): TestProject = {
      copy(contexts = this.contexts ++ (contexts.map(n => TestFeatureContext(name = n))))
    }
  }
  case class TestTag(name: String, description: String = "")

  case class TestSituationKey(name: String, clientId: String, clientSecret: String, enabled: Boolean)

  case class TestFeaturePatch(op: String, path: String, value: JsValue = JsNull) {
    def json: JsObject = Json.obj(
      "op"    -> op,
      "path"  -> path,
      "value" -> value
    )
  }

  case class TestSituation(
      keys: Map[String, TestSituationKey] = Map(),
      cookies: Seq[WSCookie] = Seq(),
      features: Map[String, Map[String, Map[String, String]]] = Map(),
      projects: Map[String, Map[String, String]] = Map(),
      tags: Map[String, Map[String, String]] = Map(),
      scripts: Map[String, String] = Map()
  ) {
    def patchFeatures(tenant: String, patches: Seq[TestFeaturePatch]) = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/features")
          .withCookies(cookies: _*)
          .patch(Json.toJson(patches.map(p => p.json)))
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def logout(): TestSituation = {
      copy(cookies = Seq())
    }

    def loggedAs(user: String, password: String): TestSituation = {
      val res = BaseAPISpec.this.login(user, password)
      copy(cookies = res.cookies)
    }

    def loggedAsAdmin(): TestSituation = {
      val res = BaseAPISpec.this.login(ALL_RIGHTS_USERNAME, ALL_RIGHTS_USERNAME_PASSWORD)
      copy(cookies = res.cookies)
    }

    def resetPassword(email: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/password/_reset")
          .withCookies(cookies: _*)
          .post(
            Json.obj(
              "email" -> email
            )
          )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def reinitializePassword(password: String, token: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/password/_reinitialize")
          .withCookies(cookies: _*)
          .post(
            Json.obj(
              "password" -> password,
              "token"    -> token
            )
          )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def pathForScript(name: String): Option[String] = {
      scripts.get(name)
    }

    def findFeatureId(tenant: String, project: String, feature: String): Option[String] = {
      for (
        tenantContent  <- features.get(tenant);
        projectContent <- tenantContent.get(project);
        featureId      <- projectContent.get(feature)
      ) yield featureId
    }

    def findProjectId(tenant: String, project: String): Option[String] = {
      for (
        tenantContent  <- projects.get(tenant);
        projectContent <- tenantContent.get(project)
      ) yield projectContent
    }

    def findTagId(tenant: String, tag: String): Option[String] = {
      for (
        tenantContent <- tags.get(tenant);
        tagContent    <- tenantContent.get(tag)
      ) yield tagContent
    }

    def createTenant(name: String, description: String = null): RequestResult = {
      BaseAPISpec.this.createTenant(name, description, cookies)
    }

    def updateTenant(oldName: String, newName: String = null, description: String = ""): RequestResult = {
      val name     = Objects.requireNonNull(newName, oldName)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${oldName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "name"        -> name,
              "description" -> description
            )
          )
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def createProject(name: String, tenant: String, description: String = null): RequestResult = {
      BaseAPISpec.this.createProject(name, tenant, description, cookies)
    }

    def updateProject(
        tenant: String,
        oldName: String,
        name: String = null,
        description: String = null
    ): RequestResult = {
      val newName  = Objects.requireNonNullElse(name, oldName)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${oldName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "name"        -> newName,
              "description" -> description
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def createTag(name: String, tenant: String, description: String = ""): RequestResult = {
      BaseAPISpec.this.createTag(name, tenant, description, cookies)
    }

    def createLegacyFeature(
        id: String,
        name: String,
        project: String,
        tenant: String,
        enabled: Boolean = true,
        tags: Seq[String] = Seq(),
        strategy: String = "NO_STRATEGY",
        parameters: JsObject = Json.obj()
    ): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/features")
          .withCookies(cookies: _*)
          .post(Json.parse(s"""{
             |"id": "$id",
             |"name": "$name",
             |"tags": [${tags.map(name => s""""${name}"""").mkString(",")}],
             |"enabled": $enabled,
             |"activationStrategy": "$strategy",
             |"parameters": ${parameters.toString()}
             |}""".stripMargin))
      )

      RequestResult(Try { response.json }, status = response.status)
    }

    def readFeatureAsLegacy(
        pattern: String,
        clientId: String,
        clientSecret: String,
        body: Option[JsValue] = None
    ): RequestResult = {
      val response = await(
        body
          .map(json => {
            ws.url(s"${BASE_URL}/features/${pattern}/check")
              .withHttpHeaders(("Izanami-Client-Id" -> clientId), ("Izanami-Client-Secret" -> clientSecret))
              .post(json)
          })
          .getOrElse(
            ws.url(s"${BASE_URL}/features/${pattern}/check")
              .withHttpHeaders(("Izanami-Client-Id" -> clientId), ("Izanami-Client-Secret" -> clientSecret))
              .get()
          )
      )

      RequestResult(Try { response.json }, status = response.status)
    }

    def checkFeaturesLegacy(
        pattern: String,
        payload: JsObject,
        clientId: String,
        clientSecret: String,
        active: Boolean = false,
        page: Int = 1,
        pageSize: Int = 100
    ): RequestResult = {
      val response = await(
        ws
          .url(s"${BASE_URL}/features/_checks?pattern=$pattern&active=$active&pageSize=$pageSize&page=$page")
          .withHttpHeaders(
            ("Izanami-Client-Id"     -> clientId),
            ("Izanami-Client-Secret" -> clientSecret)
          )
          .post(payload)
      )

      RequestResult(
        Try {
          response.json
        },
        status = response.status
      )
    }

    def readFeaturesAsLegacy(
        pattern: String,
        clientId: String,
        clientSecret: String,
        active: Boolean = false,
        page: Int = 1,
        pageSize: Int = 100
    ): RequestResult = {
      val response = await(
        ws
          .url(s"${BASE_URL}/features?pattern=$pattern&active=$active&pageSize=$pageSize&page=$page")
          .withHttpHeaders(
            ("Izanami-Client-Id"     -> clientId),
            ("Izanami-Client-Secret" -> clientSecret)
          )
          .get()
      )

      RequestResult(
        Try {
          response.json
        },
        status = response.status
      )
    }

    def createFeature(
        name: String,
        project: String,
        tenant: String,
        enabled: Boolean = true,
        tags: Seq[String] = Seq(),
        metadata: JsObject = JsObject.empty,
        conditions: Set[TestCondition] = Set(),
        wasmConfig: TestWasmConfig = null,
        id: String = null
    ): RequestResult = {
      BaseAPISpec.this.createFeature(
        name,
        project,
        tenant,
        enabled,
        tags,
        metadata,
        conditions,
        wasmConfig,
        id,
        cookies
      )
    }

    def updateFeature(tenant: String, id: String, json: JsValue): RequestResult = {
      BaseAPISpec.this.updateFeature(tenant, id, json, cookies)
    }

    def updateAPIKey(
        tenant: String,
        currentName: String,
        newName: String = null,
        description: String,
        projects: Seq[String],
        enabled: Boolean,
        admin: Boolean
    ): RequestResult = {
      BaseAPISpec.this.updateAPIKey(tenant, currentName, newName, description, projects, enabled, admin, cookies)
    }

    def deleteAPIKey(tenant: String, name: String): RequestResult = {
      BaseAPISpec.this.deleteAPIKey(tenant, name, cookies)
    }

    def fetchWasmManagerScripts(): RequestResult = {
      val response = await(ws.url("http://localhost:5001/api/plugins").get())

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def deleteScript(tenant: String, script: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts/${script}").withCookies(cookies: _*).delete()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def updateScript(tenant: String, script: String, newConfig: TestWasmConfig): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts/${script}")
          .withCookies(cookies: _*)
          .put(newConfig.json)
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def fetchTenantScripts(tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/local-scripts").withCookies(cookies: _*).get()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def createFeatureWithRawConditions(
        name: String,
        project: String,
        tenant: String,
        enabled: Boolean = true,
        tags: Seq[String] = Seq(),
        metadata: JsObject = JsObject.empty,
        conditions: String
    ): RequestResult = {
      BaseAPISpec.this.createFeatureWithRawConditions(
        name,
        project,
        tenant,
        enabled,
        tags,
        metadata,
        conditions,
        cookies = cookies
      )
    }

    def createAPIKey(
        tenant: String,
        name: String,
        projects: Seq[String] = Seq(),
        enabled: Boolean = true,
        admin: Boolean = false,
        description: String = ""
    ): RequestResult = {
      BaseAPISpec.this.createAPIKey(tenant, name, projects, description, enabled, admin, cookies)
    }

    def changeFeatureStrategyForContext(
        tenant: String,
        project: String,
        contextPath: String,
        feature: String,
        enabled: Boolean,
        conditions: Set[TestCondition] = Set(),
        wasmConfig: TestWasmConfig = null
    ) = {
      BaseAPISpec.this.changeFeatureStrategyForContext(
        tenant,
        project,
        contextPath,
        feature,
        enabled,
        conditions,
        wasmConfig,
        cookies
      )
    }

    def deleteFeatureOverload(tenant: String, project: String, path: String, feature: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts/${path}/features/${feature}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteFeatureOverloadForGlobalContext(tenant: String, path: String, feature: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/contexts/${path}/features/${feature}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def deleteContext(tenant: String, project: String, path: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/contexts/${path}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteGlobalContext(tenant: String, path: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/contexts/${path}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def createContext(
        tenant: String,
        project: String,
        name: String,
        parents: String = ""
    ): RequestResult = {
      BaseAPISpec.this.createContext(tenant, project, name, parents, cookies)
    }

    def createGlobalContext(
        tenant: String,
        name: String,
        parents: String = ""
    ): RequestResult = {
      BaseAPISpec.this.createGlobalContext(tenant, name, parents, cookies)
    }

    def fetchTag(tenant: String, name: String): RequestResult = {
      BaseAPISpec.this.fetchTag(tenant, name, cookies)
    }

    def fetchTags(tenant: String): RequestResult = {
      BaseAPISpec.this.fetchTags(tenant, cookies)
    }

    def fetchAPIKeys(tenant: String): RequestResult = {
      BaseAPISpec.this.fetchAPIKeys(tenant, cookies)
    }

    def fetchProjects(tenant: String): RequestResult = {
      BaseAPISpec.this.fetchProjects(tenant, cookies)
    }

    def evaluateFeaturesAsLoggedInUser(
        tenant: String,
        oneTagIn: Seq[String] = Seq(),
        allTagsIn: Seq[String] = Seq(),
        noTagIn: Seq[String] = Seq(),
        projects: Seq[String] = Seq(),
        features: Seq[(String, String)] = Seq(),
        context: String = "",
        user: String = "",
        date: Instant = null
    ): RequestResult = {
      val url = s"${ADMIN_BASE_URL}/tenants/${tenant}/features/_test?oneTagIn=${oneTagIn
        .map(t => findTagId(tenant, t).get)
        .mkString(",")}&allTagsIn=${allTagsIn.map(t => findTagId(tenant, t).get).mkString(",")}&noTagIn=${noTagIn
        .map(t => findTagId(tenant, t).get)
        .mkString(",")}&projects=${projects.map(pname => findProjectId(tenant, pname).get).mkString(",")}&features=${features
        .map { case (project, feature) => findFeatureId(tenant, project, feature).get }
        .mkString(",")}${if (context.nonEmpty) s"&context=${context}" else ""}${if (user.nonEmpty) s"&user=${user}"
      else ""}"

      val response = await(ws.url(url).withCookies(cookies: _*).get())
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def testFeature(
        tenant: String,
        feature: TestFeature,
        date: OffsetDateTime = OffsetDateTime.now(),
        user: String = null
    ): RequestResult = {
      BaseAPISpec.this.testFeature(tenant, feature, date, user, cookies)
    }

    def testExistingFeature(
        tenant: String,
        featureId: String,
        context: String = "",
        date: OffsetDateTime = OffsetDateTime.now(),
        user: String = null
    ): RequestResult = {
      val response = await(
        ws.url(s"""${ADMIN_BASE_URL}/tenants/${tenant}/features/${featureId}/test${if (context.nonEmpty) s"/${context}"
        else ""}?date=${DateTimeFormatter.ISO_INSTANT
          .format(date)}${if (user != null)
          s"&user=${user}"
        else ""}""")
          .withCookies(cookies: _*)
          .get()
      )

      val jsonTry = Try {
        response.json
      }
      RequestResult(json = jsonTry, status = response.status)
    }

    def fetchContexts(tenant: String, project: String) = {
      BaseAPISpec.this.fetchContexts(tenant, project, cookies)
    }

    def fetchTenants(right: String = null): RequestResult = {
      BaseAPISpec.this.fetchTenants(right, cookies)
    }

    def fetchTenant(id: String): RequestResult = {
      BaseAPISpec.this.fetchTenant(id, cookies)
    }

    def checkFeatures(
        key: String,
        projects: Seq[String] = Seq(),
        oneTagIn: Seq[String] = Seq(),
        allTagsIn: Seq[String] = Seq(),
        noTagIn: Seq[String] = Seq(),
        user: String = null,
        contextPath: String = null,
        features: Seq[String] = Seq(),
        conditions: Boolean = false
    ): RequestResult = {
      BaseAPISpec.this.checkFeatures(
        headers = keyHeaders(key),
        projects = projects,
        oneTagIn = oneTagIn,
        allTagsIn = allTagsIn,
        noTagIn = noTagIn,
        user = user,
        contextPath = contextPath,
        features = features,
        conditions = conditions
      )
    }

    def checkFeaturesWithRawKey(
        clientId: String,
        clientSecret: String,
        projects: Seq[String] = Seq(),
        oneTagIn: Seq[String] = Seq(),
        allTagsIn: Seq[String] = Seq(),
        noTagIn: Seq[String] = Seq(),
        user: String = null,
        contextPath: String = null,
        features: Seq[String] = Seq()
    ): RequestResult = {
      BaseAPISpec.this.checkFeatures(
        headers = keyHeaders(clientId, clientSecret),
        projects = projects,
        oneTagIn = oneTagIn,
        allTagsIn = allTagsIn,
        noTagIn = noTagIn,
        user = user,
        contextPath = contextPath,
        features = features
      )
    }

    def checkFeature(
        id: String,
        key: String,
        user: String = null,
        context: String = null,
        conditions: Boolean = false
    ): RequestResult = {
      BaseAPISpec.this.checkFeature(id, user, headers = keyHeaders(key), context = context, conditions = conditions)
    }

    def keyHeaders(name: String): Map[String, String] = {
      val key = keys(name)
      Map("Izanami-Client-Id" -> key.clientId, "Izanami-Client-Secret" -> key.clientSecret)
    }

    def keyHeaders(clientId: String, clientSecret: String): Map[String, String] = {
      Map("Izanami-Client-Id" -> clientId, "Izanami-Client-Secret" -> clientSecret)
    }

    def fetchProject(tenant: String, projectId: String): RequestResult = {
      BaseAPISpec.this.fetchProject(tenant, projectId, cookies)
    }

    def createUser(
        user: String,
        password: String,
        admin: Boolean = false,
        rights: TestRights = null,
        email: String = null
    ): RequestResult = {
      BaseAPISpec.this.createUser(user, password, admin, rights, email, cookies)
    }

    def createUserWithToken(
        user: String,
        password: String,
        token: String
    ): RequestResult = {
      BaseAPISpec.this.createUserWithToken(user, password, token)
    }

    def updateUserInformation(
        oldName: String,
        email: String,
        password: String,
        newName: String = null
    ): RequestResult = {
      val name     = Option(newName).getOrElse(oldName)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${oldName}")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "username" -> name,
              "email"    -> email,
              "password" -> password
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateUserRightsForTenant(
        name: String,
        rights: TestTenantRight
    ): RequestResult = {
      val jsonRight = rights.json
      val response  = await(
        ws.url(s"${ADMIN_BASE_URL}/${rights.name}/users/${name}/rights")
          .withCookies(cookies: _*)
          .put(jsonRight)
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateUserRightsForProject(
        name: String,
        tenant: String,
        project: String,
        level: String = null
    ): RequestResult = {
      val payload  = if (Objects.isNull(level)) Json.obj() else Json.obj("level" -> level)
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/users/${name}/rights")
          .withCookies(cookies: _*)
          .put(payload)
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def updateUserRights(
        name: String,
        admin: Boolean,
        rights: TestRights
    ): RequestResult = {
      val jsonRights = Option(rights).getOrElse(TestRights()).json
      val response   = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${name}/rights")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "admin"  -> admin,
              "rights" -> jsonRights
            )
          )
      )

      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateUserPassword(user: String, oldPassword: String, newPassword: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}/password")
          .withCookies(cookies: _*)
          .put(
            Json.obj(
              "password"    -> newPassword,
              "oldPassword" -> oldPassword
            )
          )
      )

      RequestResult(json = Try { response.json }, status = response.status)
    }

    def sendInvitation(email: String, admin: Boolean = false, rights: TestRights = null): RequestResult = {
      BaseAPISpec.this.sendInvitation(email = email, admin = admin, rights = rights, cookies = cookies)
    }

    def fetchUserRights(): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/rights")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def fetchUser(user: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${user}")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def searchUsers(search: String, count: Integer): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/search?query=${search}&count=${count}")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchUserForTenant(user: String, tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/${tenant}/users/${user}")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def fetchUsersForTenant(tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/users")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def inviteUsersToTenants(tenant: String, users: Seq[(String, String)]): RequestResult = {
      val payload  = Json.toJson(users.map { case (username, level) =>
        Json.obj("username" -> username, "level" -> level)
      })
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/users")
          .withCookies(cookies: _*)
          .post(payload)
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def inviteUsersToProject(tenant: String, project: String, users: Seq[(String, String)]): RequestResult = {
      val payload  = Json.toJson(users.map { case (username, level) =>
        Json.obj("username" -> username, "level" -> level)
      })
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/users")
          .withCookies(cookies: _*)
          .post(payload)
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def fetchUsersForProject(tenant: String, project: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}/users")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def deleteFeature(tenant: String, id: String): RequestResult = {
      BaseAPISpec.this.deleteFeature(tenant, id, cookies)
    }

    def fetchUsers(): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteUser(username: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/users/${username}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteTenant(tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteProject(project: String, tenant: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/projects/${project}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def deleteTag(tenant: String, name: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/tags/${name}")
          .withCookies(cookies: _*)
          .delete()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateConfiguration(
        mailProvider: String = "Console",
        invitationMode: String = "Response",
        originEmail: String = null
    ): RequestResult = {
      BaseAPISpec.this.updateConfiguration(mailProvider, invitationMode, originEmail, cookies)
    }

    def fetchConfiguration(): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/configuration")
          .withCookies(cookies: _*)
          .get()
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

    def updateMailerConfiguration(mailer: String, configuration: JsObject): RequestResult =
      BaseAPISpec.this.updateMailerConfiguration(mailer, configuration, cookies)

    def readMailerConfiguration(mailer: String): RequestResult = {
      val response = await(ws.url(s"${ADMIN_BASE_URL}/configuration/mailer/${mailer}").withCookies(cookies: _*).get())

      RequestResult(json = Try { response.json }, status = response.status)
    }

    private def writeTemporaryFile(prefix: String, suffix: String, content: Seq[String]): File = {
      val file   = File.createTempFile(prefix, suffix)
      val writer = new BufferedWriter(new FileWriter(file))
      writer.write(content.mkString("\n"))
      writer.close();

      file
    }

    def deleteImportResult(tenant: String, id: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/_import/$id")
          .withCookies(cookies: _*)
          .delete()
      )

      RequestResult(
        json = Try {
          response.json
        },
        status = response.status
      )
    }

    def importAndWaitTermination(
        tenant: String,
        timezone: String = "Europe/Paris",
        conflictStrategy: String = "FAIL",
        deduceProject: Boolean = true,
        project: String = null,
        projectPartSize: Option[Int] = None,
        features: Seq[String] = Seq(),
        users: Seq[String] = Seq(),
        keys: Seq[String] = Seq(),
        scripts: Seq[String] = Seq(),
        inlineScript: Boolean = false
    ): RequestResult = {

      val response = importV1Data(
        tenant,
        timezone,
        conflictStrategy,
        deduceProject,
        project,
        projectPartSize,
        features,
        users,
        keys,
        scripts,
        inlineScript
      )

      def request(): RequestResult = {
        checkImportStatus(tenant, response.id.get)
      }

      val promise = Promise[RequestResult]()

      Future {
        var checkResponse = request()
        while (true) {
          if ((checkResponse.json.get \ "status").as[String] != "Pending") {
            promise.success(checkResponse)
          }
          Thread.sleep(200)
          checkResponse = request()
        }
      }

      await(promise.future)(120.seconds)
    }

    def checkImportStatus(tenant: String, id: String): RequestResult = {
      val response = await(
        ws.url(s"${ADMIN_BASE_URL}/tenants/${tenant}/_import/$id")
          .withCookies(cookies: _*)
          .get()
      )

      RequestResult(json = Try { response.json }, status = response.status)
    }

    def importV1Data(
        tenant: String,
        timezone: String = "Europe/Paris",
        conflictStrategy: String = "FAIL",
        deduceProject: Boolean = true,
        project: String = null,
        projectPartSize: Option[Int] = None,
        features: Seq[String] = Seq(),
        users: Seq[String] = Seq(),
        keys: Seq[String] = Seq(),
        scripts: Seq[String] = Seq(),
        inlineScript: Boolean = false
    ): RequestResult = {
      shouldCleanUpWasmServer = true
      val featureFile = writeTemporaryFile("features", "ndjson", features)
      val userFile    = writeTemporaryFile("users", "ndjson", users)
      val keyFile     = writeTemporaryFile("keys", "ndjson", keys)
      val scriptFile  = writeTemporaryFile("scripts", "ndjson", scripts)

      val response = await(
        ws.url(
          s"${ADMIN_BASE_URL}/tenants/${tenant}/_import?timezone=${timezone}&conflict=${conflictStrategy}&deduceProject=${deduceProject}${Option(project)
            .map(p => s"&project=${p}")
            .getOrElse("")}${projectPartSize.map(p => s"&projectPartSize=${p}").getOrElse("")}&inlineScript=${inlineScript}"
        ).withCookies(cookies: _*)
          .post(
            Source(
              Seq(
                FilePart("features", "features.ndjson", Option("text/plain"), FileIO.fromPath(featureFile.toPath)),
                FilePart("users", "users.ndjson", Option("text/plain"), FileIO.fromPath(userFile.toPath)),
                FilePart("keys", "keys.ndjson", Option("text/plain"), FileIO.fromPath(keyFile.toPath)),
                FilePart("scripts", "scripts.ndjson", Option("text/plain"), FileIO.fromPath(scriptFile.toPath))
              )
            )
          )
      )
      RequestResult(json = Try { response.json }, status = response.status)
    }

  }

  case class TestUser(
      username: String,
      password: String = "barbar123",
      admin: Boolean = false,
      rights: TestRights = null,
      email: String = null
  ) {
    def withAdminRights          = copy(admin = true)
    def withEmail(email: String) = copy(email = email)
    def withTenantReadRight(tenant: String) = {
      val newRights = Option(rights).getOrElse(TestRights())
      copy(rights = newRights.addTenantRight(tenant, "Read"))
    }
    def withTenantReadWriteRight(tenant: String) = {
      val newRights = Option(rights).getOrElse(TestRights())
      copy(rights = newRights.addTenantRight(tenant, "Write"))
    }
    def withTenantAdminRight(tenant: String) = {
      val newRights = Option(rights).getOrElse(TestRights())
      copy(rights = newRights.addTenantRight(tenant, "Admin"))
    }
    def withProjectReadRight(project: String, tenant: String) = {
      copy(rights = rights.addProjectRight(project, tenant, "Read"))
    }
    def withProjectReadWriteRight(project: String, tenant: String) = {
      copy(rights = rights.addProjectRight(project, tenant, "Write"))
    }
    def withProjectAdminRight(project: String, tenant: String) = {
      copy(rights = rights.addProjectRight(project, tenant, "Admin"))
    }
    def withApiKeyReadRight(key: String, tenant: String) = {
      copy(rights = rights.addKeyRight(key, tenant, "Read"))
    }

    def withApiKeyReadWriteRight(key: String, tenant: String) = {
      copy(rights = rights.addKeyRight(key, tenant, "Write"))
    }

    def withApiKeyAdminRight(key: String, tenant: String) = {
      copy(rights = rights.addKeyRight(key, tenant, "Admin"))
    }
  }

  case class TestConfiguration(mailer: String, invitationMode: String, originEmail: String)

  case class TestWasmScript(name: String, content: String)

  val ALL_RIGHTS_USERNAME          = "RESERVED_ADMIN_USER"
  val ALL_RIGHTS_USERNAME_PASSWORD = "ADMIN_DEFAULT_PASSWORD"

  case class TestSituationBuilder(
      tenants: Set[TestTenant] = Set(),
      users: Set[TestUser] = Set(),
      loggedInUser: Option[String] = None,
      configuration: TestConfiguration =
        TestConfiguration(mailer = "Console", invitationMode = "Response", originEmail = null),
      mailerConfigurations: Map[String, JsObject] = Map(),
      wasmScripts: Seq[TestWasmScript] = Seq()
  ) {

    def withWasmScript(
        name: String,
        content: String
    ): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpWasmServer = true
      copy(wasmScripts = this.wasmScripts.appended(TestWasmScript(name, content)))
    }

    def withAllwaysActiveWasmScript(name: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpWasmServer = true
      withWasmScript(
        name,
        s"""export function execute() {
           |  Host.outputString("true");
           |  return 0;
           |}
           |
           |""".stripMargin
      )
    }

    def withAllwaysInactiveWasmScript(name: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpWasmServer = true
      withWasmScript(
        name,
        s"""export function execute() {
           |  Host.outputString("false");
           |  return 0;
           |}
           |
           |""".stripMargin
      )
    }

    /*def withWasmScript(enabled: Boolean = true): TestSituationBuilder = {
      val name = if (enabled) "izanami-enabled" else "izanami-disabled"
      copy(wasmScripts = wasmScripts + (name -> enabled))
    }*/

    def withMailProvider(provider: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(configuration = configuration.copy(mailer = provider))
    }

    def withOriginEmail(email: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(configuration = configuration.copy(originEmail = email))
    }

    def withMailerConfiguration(mailer: String, configuration: JsObject): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(mailerConfigurations = mailerConfigurations + (mailer -> configuration))
    }

    def withInvitationMode(invitationMode: String): TestSituationBuilder = {
      BaseAPISpec.this.shouldCleanUpMails = true
      copy(configuration = configuration.copy(invitationMode = invitationMode))
    }

    def loggedAs(username: String): TestSituationBuilder = {
      copy(loggedInUser = Some(username))
    }

    def loggedInWithAdminRights(): TestSituationBuilder = {
      copy(loggedInUser = Some(ALL_RIGHTS_USERNAME))
    }

    def withUsers(users: TestUser*): TestSituationBuilder = {
      copy(users = this.users ++ users)
    }

    def withTenants(tenants: TestTenant*): TestSituationBuilder = {
      copy(tenants = this.tenants ++ tenants)
    }

    def withTenantNames(tenants: String*): TestSituationBuilder = {
      copy(tenants = this.tenants ++ tenants.map(t => TestTenant(name = t)))
    }

    def build(): TestSituation = {

      var scriptIds: Map[String, String]                         = Map()
      var keyData: Map[String, TestSituationKey]                 = Map()
      val featuresData: TrieMap[String, TrieMap[
        String,
        TrieMap[String, String]
      ]]                                                         = scala.collection.concurrent.TrieMap()
      val projectsData: TrieMap[String, TrieMap[String, String]] =
        TrieMap()
      val tagsData: TrieMap[String, TrieMap[String, String]]     =
        TrieMap()

      val buildCookies = {
        val response = BaseAPISpec.this.login(ALL_RIGHTS_USERNAME, ALL_RIGHTS_USERNAME_PASSWORD)
        response.cookies.toArray.to(scala.collection.immutable.Seq)
      }

      val futures: ArrayBuffer[Future[Any]] = ArrayBuffer()

      val wasmManagerFuture = ws
        .url(s"${ADMIN_BASE_URL}/local-scripts/_cache")
        .withCookies(buildCookies: _*)
        .delete()
        .flatMap(_ => {
          val wasmManagerClient = WasmManagerClient(ws, "http://localhost:5001")
          wasmScripts.foldLeft(Future.successful(())) {
            case (future, TestWasmScript(name, fileContent)) => {
              future
                .flatMap(_ => wasmManagerClient.createScript(name, fileContent, local = false))
                .map(ids => {
                  //scriptIds = scriptIds + (name -> ids._2);
                  ids._2
                })
            }
          }
        })
      futures.addOne(wasmManagerFuture)

      val tenantFuture = Future.sequence(tenants.map(tenant => {
        tagsData.put(tenant.name, TrieMap())
        featuresData.put(tenant.name, TrieMap())
        projectsData.put(tenant.name, TrieMap())
        createTenantAsync(name = tenant.name, description = tenant.description, cookies = buildCookies)
          .map { res =>
            if (res.status >= 400) {
              throw new RuntimeException("Failed to create tenant")
            } else ()
          }
          .flatMap(_ => {
            Future
              .sequence(tenant.contexts.map(ctx => {
                createGlobalContextHierarchyAsync(tenant.name, ctx, cookies = buildCookies)
              }))
              .flatMap(_ =>
                Future.sequence(tenant.tags.map(tag => {
                  createTagAsync(
                    name = tag.name,
                    tenant = tenant.name,
                    description = tag.description,
                    cookies = buildCookies
                  )
                    .map(res => {
                      if (res.status >= 400) {
                        throw new RuntimeException("Failed to create tags")
                      } else {
                        val id = (res.json \ "id").get.as[String]
                        tagsData
                          .getOrElse(
                            tenant.name, {
                              val map = TrieMap[String, String]()
                              tagsData.put(tenant.name, map)
                              map
                            }
                          )
                          .put(tag.name, id)
                      }
                    })
                }))
              )
              .flatMap(_ =>
                Future.sequence(tenant.projects.map(project => {
                  createProjectAsync(
                    name = project.name,
                    tenant = tenant.name,
                    description = project.description,
                    cookies = buildCookies
                  ).map(res => {
                    if (res.status >= 400) {
                      throw new RuntimeException("Failed to create projects")
                    } else {
                      val id = (res.json \ "id").get.as[String]
                      projectsData
                        .getOrElse(
                          tenant.name, {
                            val map = TrieMap[String, String]()
                            projectsData.put(tenant.name, map)
                            map
                          }
                        )
                        .put(project.name, id)
                    }
                  }).flatMap(_ => {
                    val tenantMap  = featuresData.getOrElse(
                      tenant.name, {
                        val map = TrieMap[String, TrieMap[String, String]]()
                        featuresData.put(tenant.name, map)
                        map
                      }
                    )
                    val projectMap = tenantMap.getOrElse(
                      project.name, {
                        val map = TrieMap[String, String]()
                        tenantMap.put(project.name, map)
                        map
                      }
                    )
                    Future.sequence(
                      project.features
                        .map(feature => {
                          createFeatureAsync(
                            name = feature.name,
                            project = project.name,
                            tenant = tenant.name,
                            enabled = feature.enabled,
                            tags = feature.tags,
                            metadata = feature.metadata,
                            conditions = feature.conditions,
                            wasmConfig = feature.wasmConfig,
                            id = feature.id,
                            cookies = buildCookies
                          ).map(res => {
                            if (res.status >= 400) {
                              throw new RuntimeException("Failed to create features")
                            } else {
                              projectMap.put(feature.name, (res.json \ "id").as[String])
                            }
                          })
                        })
                        .concat(project.contexts.map(context => {
                          createContextHierarchyAsync(tenant.name, project.name, context, cookies = buildCookies)
                        }))
                    )
                  })
                }))
              )
              .flatMap(_ => {
                Future
                  .sequence(
                    tenant.apiKeys
                      .map(key => {
                        createAPIKeyAsync(
                          tenant = tenant.name,
                          name = key.name,
                          projects = key.projects,
                          description = key.description,
                          enabled = key.enabled,
                          admin = key.admin,
                          cookies = buildCookies
                        )
                      })
                      .concat(tenant.allRightKeys.map(ak => {
                        createAPIKeyAsync(
                          tenant = tenant.name,
                          name = ak,
                          projects = tenant.projects.map(p => p.name).toSeq,
                          cookies = buildCookies
                        )
                      }))
                  )
                  .map(responses => {
                    responses
                      .map(result => {
                        val json = result.json
                        TestSituationKey(
                          name = (json \ "name").as[String],
                          clientId = (json \ "clientId").as[String],
                          clientSecret = (json \ "clientSecret").as[String],
                          enabled = (json \ "enabled").as[Boolean]
                        )
                      })
                      .foreach(key => keyData = keyData + (key.name -> key))
                  })
              })
          })
      }))
      futures.addOne(tenantFuture)

      futures.addOne(
        updateConfigurationAsync(
          configuration.mailer,
          configuration.invitationMode,
          configuration.originEmail,
          buildCookies
        )
          .map(configurationResponse => {
            if (configurationResponse.status >= 400) {
              throw new Exception("Failed to update configuration")
            } else {
              ()
            }
          })
      )

      val configurationFuture = Future.sequence(mailerConfigurations.map { case (mailer, configuration) =>
        updateMailerConfigurationAsync(mailer, configuration, buildCookies)
          .map(result => {
            if (result.status >= 400) {
              throw new Exception("Failed to update mailer configuration")
            } else {
              ()
            }
          })
      })
      futures.addOne(configurationFuture)

      await(
        Future
          .sequence(futures)
          .flatMap(_ => {
            Future.sequence(users.map(user => {
              createUserAsync(
                user.username,
                user.password,
                user.admin,
                user.rights,
                email = user.email,
                cookies = buildCookies
              ).map(res => {
                if (res.status >= 400) {
                  throw new RuntimeException("Failed to create user")
                } else ()
              })
            }))
          })
      )(2.minutes)

      val cookies = {
        if (loggedInUser.exists(u => u.equals(ALL_RIGHTS_USERNAME))) {
          buildCookies
        } else {
          loggedInUser
            .map(username => {
              val user     = users.find(u => u.username.equals(username)).get
              val response = BaseAPISpec.this.login(user.username, user.password)
              response.cookies.toArray.to(scala.collection.immutable.Seq)
            })
            .getOrElse(Seq())
        }
      }

      val immutableFeatureData = featuresData.view.mapValues(v => v.view.mapValues(vv => vv.toMap).toMap).toMap
      val immutableProjectData = projectsData.view.mapValues(v => v.toMap).toMap
      val immutableTagData     = tagsData.view.mapValues(v => v.toMap).toMap

      TestSituation(
        keys = keyData,
        cookies = cookies,
        features = immutableFeatureData,
        projects = immutableProjectData,
        tags = immutableTagData,
        scripts = scriptIds
      )
    }
  }
}

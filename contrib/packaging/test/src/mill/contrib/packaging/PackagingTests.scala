package mill.contrib.packaging

import java.util
import ammonite.ops._
import ammonite.ops.ImplicitWd._
import java.util.jar.JarFile
import java.util.zip.{ZipEntry, ZipFile, ZipInputStream}
import java.io.{File, FileInputStream, FileOutputStream}

import mill._
import mill.define.{Discover, Target}
import mill.eval.Result._
import mill.eval.{Evaluator, Result}
import mill.modules.Assembly
import mill.scalalib._
import mill.scalalib.publish.VersionControl
import mill.scalalib.publish._
import mill.util.{TestEvaluator, TestUtil}

import scala.collection.JavaConverters._
import utest._
import utest.framework.TestPath

import scala.tools.nsc.interpreter.InputStream

object PackagingTests extends TestSuite {

  val scalaVersionString = "2.12.6"

  trait PackageZipModule extends TestUtil.BaseModule with scalalib.ScalaModule {
    def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    def scalaVersion = scalaVersionString
  }

  object HelloWorldPackageZip extends PackageZipModule {

    object core extends scalalib.ScalaModule {
      def scalaVersion = "2.12.4"
      def ivyDeps = super.ivyDeps() ++ Agg(ivy"com.lihaoyi::sourcecode:0.1.3")
    }

    object app extends scalalib.ScalaModule with Packaging {
      def scalaVersion = "2.12.4"
      def moduleDeps = Seq(core)
      def ivyDeps = Agg(
        ivy"com.lihaoyi::sourcecode:0.1.4",
        ivy"com.github.julien-truffaut::monocle-macro::1.4.0")
      def compileIvyDeps = Agg(ivy"io.chrisdavenport::log4cats-core:0.0.4")
      def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
        ivy"org.scalamacros:::paradise:2.1.0"
      )
      def forkArgs = Seq("-Xmx1024m")

    }

  }

  val resourcePath = pwd / 'contrib / 'packaging /'test / 'resources

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: Path = resourcePath)
                      (t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {


    'packaging - {

      'zip - workspaceTest(HelloWorldPackageZip){ eval =>
        val Right((ref, _)) = eval.apply(HelloWorldPackageZip.app.packageZip)

        val Right((runClasspath, _)) = eval.apply(HelloWorldPackageZip.app.runClasspath)


        //unzip out.zip

        val buffer = new Array[Byte](1024)

        try {
          val outputFolder = new File((ref.path / up / 'tmp).toString)
          if(!outputFolder.exists()){
            outputFolder.mkdir()
          }

          val zipInputStream: ZipInputStream = new ZipInputStream(new FileInputStream(ref.path.toString))
          var zipEntry: ZipEntry = zipInputStream.getNextEntry

          while(zipEntry != null) {

            val fileName = zipEntry.getName
            val newFile = new File(outputFolder + File.separator + fileName)

            new File(newFile.getParent).mkdirs()

            val fileOutputStream = new FileOutputStream(newFile)

            var length: Int = zipInputStream.read(buffer)

            while(length > 0) {
              fileOutputStream.write(buffer, 0, length)
              length = zipInputStream.read(buffer)
            }

            fileOutputStream.close()
            zipEntry = zipInputStream.getNextEntry
          }

          zipInputStream.closeEntry()
          zipInputStream.close()

        } catch {
          case e: Throwable => System.err.println(e)
        }


        val jars = (ls! (ref.path / up / 'tmp / 'lib)).map(_.segments.last)


        //checks that all jars are in the lib folder

        //jar of the module itself
        assert(jars.contains("app.jar"))

        //moduleDeps jars
        assert(jars.contains("core.jar"))

        //dependency jars
        runClasspath
          .map(_.path)
          .filter(path => exists(path) && path.name.endsWith(".jar"))
          .map(path => path.segments.last)
          .foreach(path => assert(jars.contains(path)))

        //check that dependencies only needed at compile time are not inside of the lib folder

        assert(!jars.contains("log4cats-core_2.12-0.0.4.jar"))
        assert(!jars.contains("paradise_2.12-2.1.0.jar"))

        //check that jvm options are in conf/application.ini
        assert(read! (ref.path / up / 'tmp / 'conf / "application.ini") == "-Xmx1024m")

        //check that the script was generated correctly
        val script = read! (ref.path / up / 'tmp / 'bin / "template-script")

        //check mainClass
        assert(script.contains("declare -a app_mainclass=(hello.Main)"))

        //check availableMainClasses
        assert(script.contains("hello.Main\nEOM"))

        //check classPath
        val pattern = """(?s)(.*)declare -r app_classpath="(.*?)"(.*)""".r
        val pattern(_, templateDeclares, _) = script

        runClasspath
          .map(_.path)
          .filter(path => exists(path) && path.name.endsWith(".jar"))
          .map(path => path.segments.last)
          .foreach(path => assert(templateDeclares.contains("$lib_dir/" ++ path)))


      }
    }
  }
}
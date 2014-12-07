/* Copyright 2012-2014 Micronautics Research Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License. */

package com.micronautics.aws

import java.io.{File, InputStream}
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.{BasicFileAttributeView, FileTime}
import java.util.Date

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.policy.{Policy, Statement}
import com.amazonaws.event.ProgressEventType
import com.amazonaws.services.identitymanagement.model.{User => IAMUser}
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.amazonaws.{AmazonClientException, HttpMethod, event}
import com.micronautics.aws.Util._
import org.joda.time.{DateTime, Duration}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
 * When uploading, any leading slashes for keys are removed because when AWS S3 is enabled for a web site, S3 adds a leading slash.
 *
 * Keys of assets that were uploaded by other clients might start with leading slashes, or a dit; those assets can
 * not be fetched by web browsers.
 *
 * AWS does not respect the last-modified metadata provided when uploading; it uses the upload timestamp instead.
 * After uploading, the last-modified timestamp of the uploaded file is read and applied to the local copy of the file
 * so the timestamps match.
 *
 * Java on Windows does not handle last-modified properly, so the creation date is set to the last-modified date for files (Windows only).
 */
object S3 {
  def apply(implicit awsCredentials: AWSCredentials, s3Client: AmazonS3Client = new AmazonS3Client): S3 =
    new S3()(awsCredentials, s3Client)

  protected def bucketPolicy(bucketName: String): String = s"""{
    |\t"Version": "2008-10-17",
    |\t"Statement": [
    |\t\t{
    |\t\t\t"Sid": "AddPerm",
    |\t\t\t"Effect": "Allow",
    |\t\t\t"Principal": {
    |\t\t\t\t"AWS": "*"
    |\t\t\t},
    |\t\t\t"Action": "s3:GetObject",
    |\t\t\t"Resource": "arn:aws:s3:::$bucketName/*"
    |\t\t}
    |\t]
    |}
    |""".stripMargin

  /** @param key any leading slashes are removed so the key can be used as a relative path */
  def relativize(key: String): String = sanitizePrefix(key)

  def safeNamesFor(bucket: Bucket): (String, String) = {
    val lcBucketName = bucket.getName.toLowerCase
    (lcBucketName, s"S3-$lcBucketName")
  }

  def sanitizePrefix(key: String): String = key.substring(key.indexWhere(_ != '/')).replace("//", "/")
}

class S3()(implicit val awsCredentials: AWSCredentials, val s3Client: AmazonS3Client=new AmazonS3Client) {
  import com.micronautics.aws.S3._

  /** @param prefix Any leading slashes are removed if a prefix is specified
    * @return collection of S3ObjectSummary; keys are relativized if prefix is adjusted */
  def allObjectData(bucketName: String, prefix: String): List[S3ObjectSummary] = {
    val sanitizedPre = sanitizedPrefix(prefix)

    @tailrec def again(objectListing: ObjectListing, accum: List[S3ObjectSummary]): List[S3ObjectSummary] = {
      val result: List[S3ObjectSummary] = for {
        s3ObjectSummary <- objectListing.getObjectSummaries.asScala.toList
      } yield {
        if (sanitizedPre!=prefix)
          s3ObjectSummary.setKey(S3.relativize(s3ObjectSummary.getKey))
        s3ObjectSummary
      }
      if (objectListing.isTruncated)
        again(s3Client.listNextBatchOfObjects(objectListing), accum ::: result)
      else
        result
    }

    val objectListing: ObjectListing = s3Client.listObjects(new ListObjectsRequest().withBucketName(bucketName).withPrefix(prefix))
    again(objectListing, List.empty[S3ObjectSummary])
  }

  def bucketExists(bucketName: String): Boolean = s3Client.listBuckets.asScala.exists(_.getName==bucketName)

  def maybeBucketFor(bucketName: String): Option[Bucket] = try {
    s3Client.listBuckets().asScala.find(_.getName == bucketName)
  } catch {
    case e: Exception => None
  }

  def bucketLocation(bucketName: String): String = s3Client.getBucketLocation(bucketName)

  /** List the buckets in the account */
  def bucketNames: List[String] = s3Client.listBuckets.asScala.map(_.getName).toList

  def bucketNamesWithDistributions(implicit cf: CloudFront): List[String] = cf.bucketNamesWithDistributions

  def bucketsWithDistributions(implicit cf: CloudFront): List[Bucket] = cf.bucketsWithDistributions(this)

  /** Create a new S3 bucket.
    * If the bucket name starts with "www.", make it publicly viewable and enable it as a web site.
    * Amazon S3 bucket names are globally unique, so once a bucket repoName has been
    * taken by any user, you can't create another bucket with that same repoName.
    * You can optionally specify a location for your bucket if you want to keep your data closer to your applications or users. */
  def createBucket(bucketName: String): Bucket = {
    val bnSanitized: String = bucketName.toLowerCase.replaceAll("[^A-Za-z0-9.]", "")
    if (bucketName!=bnSanitized)
      println(s"Invalid characters removed from bucket name; modified to $bnSanitized")
    if (bucketExists(bnSanitized))
      throw new Exception(s"Error: Bucket '$bnSanitized' exists.")
    val bucket: Bucket = s3Client.createBucket(bnSanitized)
    s3Client.setBucketPolicy(bnSanitized, bucketPolicy(bnSanitized))
    if (bnSanitized.startsWith("www."))
      enableWebsite(bnSanitized)
    bucket
  }

  /** Delete a bucket - The bucket will automatically be emptied if necessary so it can be deleted. */
  @throws(classOf[AmazonClientException])
  def deleteBucket(bucketName: String): Unit = {
    emptyBucket(bucketName)
    s3Client.deleteBucket(bucketName)
  }

  /** Normal use case is to delete a directory and all its contents */
  def deletePrefix(bucketName: String, prefix: String): Unit = S3Scala.deletePrefix(this, bucketName, prefix)

  /** Delete an object - if they key has any leading slashes, they are removed.
    * Unless versioning has been turned on for the bucket, there is no way to undelete an object. */
  def deleteObject(bucketName: String, key: String): Unit =
    s3Client.deleteObject(bucketName, sanitizedPrefix(key))

  def disableWebsite(bucketName: String): Unit = {
    s3Client.deleteBucketWebsiteConfiguration(bucketName)
    ()
  }

  /** Download an object - if the key has any leading slashes, they are removed.
    * When you download an object, you get all of the object's metadata and a
    * stream from which to read the contents. It's important to read the contents of the stream as quickly as
    * possible since the data is streamed directly from Amazon S3 and your network connection will remain open
    * until you read all the data or close the input stream.
    *
    * GetObjectRequest also supports several other options, including conditional downloading of objects
    * based on modification times, ETags, and selectively downloading a range of an object. */
  def downloadFile(bucketName: String, key: String): InputStream = {
    val sanitizedKey = sanitizedPrefix(key).replace ("//", "/")
    val s3Object: S3Object = s3Client.getObject (new GetObjectRequest (bucketName, sanitizedKey) )
    s3Object.getObjectContent
  }

  @throws(classOf[AmazonClientException])
  def emptyBucket(bucketName: String): Unit = {
    val items: List[S3ObjectSummary] = allObjectData(bucketName, null)
    items foreach { item => s3Client.deleteObject(bucketName, item.getKey) }
  }

  /**
  <?xml version="1.0" encoding="UTF-8"?>
  <CORSConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
      <CORSRule>
          <AllowedOrigin>*</AllowedOrigin>
          <AllowedMethod>GET</AllowedMethod>
          <AllowedMethod>HEAD</AllowedMethod>
          <AllowedMethod>PUT</AllowedMethod>
          <AllowedMethod>POST</AllowedMethod>
          <AllowedMethod>DELETE</AllowedMethod>
          <AllowedHeader>*</AllowedHeader>
      </CORSRule>
  </CORSConfiguration> */
  def enableCors(bucket: Bucket): Unit =
    try {
      val rule1 = new CORSRule()
        .withAllowedMethods(List(
          CORSRule.AllowedMethods.GET,
          CORSRule.AllowedMethods.HEAD,
          CORSRule.AllowedMethods.PUT,
          CORSRule.AllowedMethods.POST,
          CORSRule.AllowedMethods.DELETE).asJava)
        .withAllowedOrigins(List("*").asJava)
        .withAllowedHeaders(List("*").asJava)
      val rules = List(rule1)
      val configuration = new BucketCrossOriginConfiguration
      configuration.setRules(rules.asJava)
      s3Client.setBucketCrossOriginConfiguration(bucket.getName, configuration)
    } catch {
      case ignored: Exception =>
        Logger.debug("Ignored: " + ignored)
    }

  def enableWebsite(bucketName: String): Unit = {
    val configuration: BucketWebsiteConfiguration = new BucketWebsiteConfiguration("index.html")
    s3Client.setBucketWebsiteConfiguration(bucketName, configuration)
    ()
  }

  def enableWebsite(bucketName: String, errorPage: String): Unit = {
    val configuration: BucketWebsiteConfiguration = new BucketWebsiteConfiguration("index.html", errorPage)
    s3Client.setBucketWebsiteConfiguration(bucketName, configuration)
  }

  /** Requires property com.amazonaws.sdk.disableCertChecking to have a value (any value will do) */
  def isWebsiteEnabled(bucketName: String): Boolean =
    try {
      s3Client.getBucketWebsiteConfiguration(bucketName) != null
    } catch {
      case e: Exception => false
    }

  /** List objects in given bucketName by prefix, followed by number of bytes.
    * @param prefix Any leading slashes are removed if a prefix is specified */
  def listObjectsByPrefix(bucketName: String, prefix: String): List[String] =
    listObjectsByPrefix(bucketName, prefix, showSize=true)

  /** List objects in given bucketName by prefix; number of bytes is included if showSize is true.
    * @param prefix Any leading slashes are removed if a prefix is specified */
  def listObjectsByPrefix(bucketName: String, prefix: String, showSize: Boolean): List[String] = {
    @tailrec def again(objectListing: ObjectListing, accum: List[String]): List[String] = {
      val result: List[String] = for {
        s3ObjectSummary <- objectListing.getObjectSummaries.asScala.toList
      } yield {
        s3ObjectSummary.getKey + (if (showSize) s" (size = ${s3ObjectSummary.getSize})" else "")
      }
      if (objectListing.isTruncated)
        again(s3Client.listNextBatchOfObjects(objectListing), accum ::: result)
      else
        result
    }

    val objectListing: ObjectListing = s3Client.listObjects(new ListObjectsRequest().withBucketName(bucketName).withPrefix(sanitizedPrefix(prefix)))
    again(objectListing, List.empty[String])
  }

  /** Move oldKey in bucket with bucketName to newKey.
    * Object metadata is preserved. */
  def move(bucketName: String, oldKey: String, newKey: String): Unit =
    move (bucketName, oldKey, bucketName, newKey)

  /** Move oldKey in bucket with bucketName to newBucket / newKey.
    * Object metadata is preserved. */
  def move(oldBucketName: String, oldKey: String, newBucketName: String, newKey: String): Unit = {
    val copyRequest: CopyObjectRequest = new CopyObjectRequest(oldBucketName, oldKey, newBucketName, newKey)
    s3Client.copyObject(copyRequest)
    val deleteRequest: DeleteObjectRequest = new DeleteObjectRequest(oldBucketName, oldKey)
    s3Client.deleteObject(deleteRequest)
  }

  /** @param prefix Any leading slashes are removed if a prefix is specified
    * @return Option[ObjectSummary] with leading "./", prepended if necessary */
  def oneObjectData(bucketName: String, prefix: String): Option[S3ObjectSummary] = {
    val sanitizedPre = sanitizedPrefix(prefix)
    val objectListing: ObjectListing = s3Client.listObjects(new ListObjectsRequest().withBucketName (bucketName).withPrefix(sanitizedPre))
    objectListing.getObjectSummaries.asScala.find { objectSummary =>
      val key: String = objectSummary.getKey
      if (key==sanitizedPre) {
        objectSummary.setKey(S3.relativize(key))
        true
      } else false
    }
  }

  def resourceUrl(bucketName: String, key: String): String = s3Client.getResourceUrl(bucketName, key)

  def sanitizedPrefix(key: String): String = if (key==null) null else key.substring(math.max(0, key.indexWhere(_!='/')))

  def setContentType(key: String): ObjectMetadata = {
    val metadata = new ObjectMetadata
    setContentType(key, metadata)
  }

  def setContentType(key: String, metadata: ObjectMetadata): ObjectMetadata = {
    metadata.setContentType(guessContentType(key))
    metadata
  }

  /** This method is idempotent
    * Side effect: sets policy for AWS S3 upload bucket */
  def setBucketPolicy(bucket: Bucket, statements: List[Statement]): Bucket =
    setBucketPolicy(bucket, new Policy().withStatements(statements: _*).toJson)

  /** This method is idempotent
    * Side effect: sets policy for AWS S3 upload bucket */
  def setBucketPolicy(bucket: Bucket, policyJson: String): Bucket = {
    //Logger.debug(s"New policy for ${bucket.getName} bucket: " + policyJson)
    try {
      s3Client.setBucketPolicy(bucket.getName, policyJson)
    } catch {
      case ignored: Exception =>
        Logger.debug(s"setBucketPolicy: $ignored")
    }
    bucket
  }

  def signUrl(bucket: Bucket, url: URL, minutesValid: Int=60): URL =
    signUrlStr(bucket, relativize(url.getFile), minutesValid)

  def signUrlStr(bucket: Bucket, key: String, minutesValid: Int=0): URL = {
    val expiry = DateTime.now.plusMinutes(minutesValid)
    val generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucket.getName, key).
      withMethod(HttpMethod.GET).withExpiration(expiry.toDate)
    val signedUrl: URL = s3Client.generatePresignedUrl(generatePresignedUrlRequest)
    new URL("http", signedUrl.getHost, signedUrl.getPort, signedUrl.getFile)
  }

  /** Uploads a file to the specified bucket. The file's last-modified date is applied to the uploaded file.
    * AWS does not respect the last-modified metadata, and Java on Windows does not handle last-modified properly either.
    * If the key has leading slashes, they are removed for consistency.
    *
    * AWS does not respect the last-modified metadata provided when uploading; it uses the upload timestamp instead.
    * After uploading, the last-modified timestamp of the uploaded file is read and applied to the local copy of the file
    * so the timestamps match.
    *
    * Java on Windows does not handle last-modified properly, so the creation date is set to the last-modified date (Windows only).
    *
    * To list the last modified date with seconds in bash with: <code>ls -lc --time-style=full-iso</code>
    * To list the creation date with seconds in bash with: <code>ls -l --time-style=full-iso</code> */
  def uploadFile(bucketName: String, key: String, file: File, showProgress: Boolean=false): PutObjectResult = {
    val metadata: ObjectMetadata = new ObjectMetadata
    metadata.setLastModified(new Date(latestFileTime(file)))
    metadata.setContentEncoding("utf-8")
    setContentType(key, metadata)
    val sanitizedKey = sanitizedPrefix(key)
    try {
      val putObjectRequest: PutObjectRequest = new PutObjectRequest(bucketName, sanitizedKey, file)
      putObjectRequest.setMetadata(metadata)
      if (showProgress) {
        putObjectRequest.setGeneralProgressListener(new event.ProgressListener {
          def progressChanged(progressEvent: event.ProgressEvent): Unit = {
            if (progressEvent.getEventType eq ProgressEventType.TRANSFER_COMPLETED_EVENT)
              println(" " + progressEvent.getBytesTransferred + " bytes; ")
            else
              println(".")
          }
        })
      }
      val result: PutObjectResult = s3Client.putObject(putObjectRequest)
      val m2: ObjectMetadata = s3Client.getObjectMetadata(bucketName, sanitizedKey)
      val time: FileTime = FileTime.fromMillis(m2.getLastModified.getTime)
      Files.getFileAttributeView(file.toPath, classOf[BasicFileAttributeView]).setTimes(time, null, time)
      result
    } catch {
      case e: Exception =>
        println(e.getMessage)
        new PutObjectResult
    }
  }

  /** Recursive upload to AWS S3 bucket
    * @param file or directory to copy
    * @param dest path to copy to on AWS S3 */
  def uploadFileOrDirectory(bucketName: String, dest: String, file: File): Unit =
    S3Scala.uploadFileOrDirectory(this, bucketName, dest, file)

  def uploadString(bucketName: String, key: String, contents: String): PutObjectResult = {
    import com.amazonaws.util.StringInputStream
    val metadata: ObjectMetadata = new ObjectMetadata
    metadata.setLastModified(new Date)
    metadata.setContentEncoding("utf-8")
    metadata.setContentLength(contents.length)
    setContentType(key, metadata)
    try {
      val inputStream: InputStream = new StringInputStream(contents)
      val putObjectRequest: PutObjectRequest = new PutObjectRequest(bucketName, sanitizedPrefix(key), inputStream, metadata)
      s3Client.putObject (putObjectRequest)
    } catch {
      case e: Exception =>
        println(e.getMessage)
        new PutObjectResult
    }
  }

  /** @param key if the key has any leading slashes, they are removed
    * @see <a href="http://docs.amazonwebservices.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/s3/model/ObjectMetadata.html">ObjectMetadata</a> */
  def uploadStream(bucketName: String, key: String, stream: InputStream, length: Int): Unit = {
    val metadata: ObjectMetadata = new ObjectMetadata
    metadata.setContentLength(length)
    metadata.setContentEncoding("utf-8")
    setContentType(key, metadata)
    s3Client.putObject(new PutObjectRequest (bucketName, sanitizedPrefix(key), stream, metadata))
    ()
  }
}

trait S3Implicits {
  implicit class RichBucket(val bucket: Bucket)(implicit val s3: S3) {
    import com.amazonaws.services.cloudfront.model.{DistributionSummary, UpdateDistributionResult}

    def allObjectData(prefix: String): List[S3ObjectSummary] = s3.allObjectData(bucket.getName, prefix)

    def exists: Boolean = s3.bucketExists(bucket.getName)

    def location: String = s3.bucketLocation(bucket.getName)

    def create(): Bucket = s3.createBucket(bucket.getName)

    @throws(classOf[AmazonClientException])
    def delete(): Unit = s3.deleteBucket(bucket.getName)

    def deleteObject(key: String): Unit = s3.deleteObject(bucket.getName, key)

    def deletePrefix(prefix: String): Unit = s3.deletePrefix(bucket.getName, prefix)

    def disableWebsite(): Unit = s3.disableWebsite(bucket.getName)

    def distributions(implicit cf: CloudFront): List[DistributionSummary] = cf.distributionsFor(bucket)

    def downloadAsStream(key: String): InputStream = s3.downloadFile(bucket.getName, key)

    def downloadAsString(key: String): String = io.Source.fromInputStream(downloadAsStream(key)).mkString

    @throws(classOf[AmazonClientException])
    def empty(): Unit = s3.emptyBucket(bucket.getName)

    def enableCors(): Unit = s3.enableCors(bucket)

    def enableAllDistributions(newStatus: Boolean=true)(implicit cf: CloudFront): List[UpdateDistributionResult] =
      cf.enableAllDistributions(bucket, newStatus)

    def enableLastDistribution(newStatus: Boolean=true)(implicit cf: CloudFront): Option[UpdateDistributionResult] =
      cf.enableLastDistribution(bucket, newStatus)

    def enableWebsite(): Unit = s3.enableWebsite(bucket.getName)

    def enableWebsite(errorPage: String): Unit = s3.enableWebsite(bucket.getName, errorPage)

    /** Invalidate asset in all bucket distributions where it is present.
        * @param assetPath The path of the objects to invalidate, relative to the distribution and must begin with a slash (/).
        *                  If the path is a directory, all assets within in are invalidated
        * @return number of asset invalidations */
    def invalidate(assetPath: String)(implicit cf: CloudFront): Int = cf.invalidate(bucket, List(assetPath))

    /** Invalidate asset in all bucket distributions where it is present.
        * @param assetPaths The path of the objects to invalidate, relative to the distribution and must begin with a slash (/).
        *                  If the path is a directory, all assets within in are invalidated
        * @return number of asset invalidations */
    def invalidate(assetPaths: List[String])(implicit cf: CloudFront): Int = cf.invalidate(bucket, assetPaths)

    def isWebsiteEnabled: Boolean = s3.isWebsiteEnabled(bucket.getName)

    def listObjectsByPrefix(prefix: String): List[String] = s3.listObjectsByPrefix(bucket.getName, prefix)

    def listObjectsByPrefix(prefix: String, showSize: Boolean): List[String] = s3.listObjectsByPrefix(bucket.getName, prefix, showSize)

    def move(oldKey: String, newKey: String): Unit = s3.move(bucket.getName, oldKey, newKey)

    def move(oldKey: String, newBucketName: String, newKey: String): Unit = s3.move(bucket.getName, oldKey, newBucketName, newKey)

    def name: String = bucket.getName

    def policy_=(policyJson: String) = s3.setBucketPolicy(bucket, policyJson)

    def policy_=(statements: List[Statement]) = s3.setBucketPolicy(bucket, statements)

    def policy: BucketPolicy = s3.s3Client.getBucketPolicy(bucket.getName)

    def policyAsJson: String = policy.getPolicyText

    def policyEncoder(policyText: String, contentLength: Long, expiryDuration: Duration=Duration.standardHours(1)): String =
      new AWSUpload(bucket, expiryDuration)(s3.awsCredentials).policyEncoder(policyText, contentLength)

    def createPolicyText(key: String, contentLength: Long, acl: String="private", expiryDuration: Duration=Duration.standardHours(1)): String =
      new AWSUpload(bucket, expiryDuration)(s3.awsCredentials).policyText(key, contentLength, acl)

    def oneObjectData(prefix: String): Option[S3ObjectSummary] = s3.oneObjectData(bucket.getName, prefix)

    def removeDistribution(bucket: Bucket)(implicit cf: CloudFront): Boolean = cf.removeDistribution(bucket)

    def resourceUrl(key: String): String = s3.resourceUrl(bucket.getName, key)

    def safeNames: (String, String) = S3.safeNamesFor(bucket)

    def signAndEncodePolicy(key: String,
                            contentLength: Long,
                            acl: String=AWSUpload.privateAcl,
                            awsSecretKey: String=s3.awsCredentials.getAWSSecretKey,
                            expiryDuration: Duration=Duration.standardHours(1)): SignedAndEncoded =
      new AWSUpload(bucket, expiryDuration)(s3.awsCredentials).signAndEncodePolicy(key, contentLength, acl, awsSecretKey)

    def signPolicy(policyText: String, contentLength: Long, awsSecretKey: String, expiryDuration: Duration=Duration.standardHours(1)): String =
      new AWSUpload(bucket, expiryDuration)(s3.awsCredentials).signPolicy(policyText, contentLength, awsSecretKey)

    def signUrl(url: URL, minutesValid: Int=60): URL = s3.signUrl(bucket, url, minutesValid)

    def signUrlStr(key: String, minutesValid: Int=0): URL = s3.signUrlStr(bucket, key, minutesValid)

    def uploadFile(key: String, file: File): PutObjectResult = s3.uploadFile(bucket.getName, key, file)

    def uploadFileOrDirectory(dest: String, file: File): Unit = s3.uploadFileOrDirectory(bucket.getName, dest, file)

    def uploadString(key: String, contents: String): PutObjectResult =  s3.uploadString(bucket.getName, key, contents)

    def uploadStream(key: String, stream: InputStream, length: Int): Unit = s3.uploadStream(bucket.getName, key, stream, length)
  }

  implicit class RichBucketIAM(val bucket: Bucket) {
    import com.amazonaws.auth.policy.Principal
    import com.amazonaws.auth.policy.actions.S3Actions

    def allowAllStatement(principals: Seq[Principal], idString: String): Statement =
      IAM.allowAllStatement(bucket, principals, idString)

    def allowSomeStatement(principals: Seq[Principal], actions: Seq[S3Actions], idString: String): Statement =
      IAM.allowSomeStatement(bucket, principals, actions, idString)
  }
}
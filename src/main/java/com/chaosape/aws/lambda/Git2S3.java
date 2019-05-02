package com.chaosape.aws.lambda;

import java.lang.InterruptedException;
import com.amazonaws.auth.AWSCredentialsProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.io.File;

import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.GitAPIException;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.DecoderException;

public class Git2S3
  implements RequestStreamHandler {

  private Map<String,String> env = null;

  public Git2S3(Map<String,String> env) {
    super();
    this.env = env;
  }

  public Git2S3() {
    super();
  }

  private void log(LambdaLogger logger, String msg) {
    if(logger != null) {
      logger.log(msg);
    } else {
      System.err.println(msg);
    }
  }

  private boolean deleteDirectory(File dir) {
    if (dir.isDirectory()) {
      File[] children = dir.listFiles();
      for (int i = 0; i < children.length; i++) {
        boolean success = deleteDirectory(children[i]);
        if (!success) {
          return false;
        }
      }
    }
    return dir.delete();
  }

  private String getEnvVar(String key) {
    if(env == null) {
      return System.getenv(key);
    } else {
      return env.get(key);
    }
  }

  @Override
  public void handleRequest(InputStream inputStream,
                            OutputStream outputStream,
                            Context context)
    throws IOException
  {

    LambdaLogger logger = null;
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode outgoingHeaderJson = mapper.createObjectNode();
    ObjectNode outgoingJsonNode = mapper.createObjectNode();
    OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8");
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    if(context != null) {
      logger = context.getLogger();
      log(logger, "Git2S3.handleRequest: logger initialized.");
    }

    log(logger, "Git2S3.handleRequest: entry.");

    // Invariant response fields.

    outgoingHeaderJson.put("Access-Control-Allow-Origin", "*");
    outgoingJsonNode.set("headers", outgoingHeaderJson);
    outgoingJsonNode.put("isBase64Encoded", false);



    try {
      // Attempt to parse JSON body.
      JsonNode incomingJsonNode = mapper.readTree(inputStream);

      log(logger,
          "Git2S3.handleRequest: Received JSON request"
            + incomingJsonNode.toString()
          );


      // Get body of request.
      JsonNode bodyNode = incomingJsonNode.get("body");
      if(bodyNode == null) {
        String msg = "'body' key not found in root JSON request.";
        throw (new IllegalArgumentException(msg));
      }

      // XXX: What follows will not work with proxy integration; since
      // we do not receive the identical proxy that was sent, i.e.,
      // the json request is put in a string and the formatting of the
      // request is changed.
      //if(getEnvVar("GITHUB_SECRET") != null) {
      //  JsonNode signatureNode =
      //    incomingJsonNode
      //    .path("headers")
      //    .path("X-Hub-Signature");
      //
      //  if(signatureNode.isMissingNode()) {
      //    String msg = "Expected 'X-Hub-Signature' header.";
      //    throw (new IllegalArgumentException(msg));
      //  }
      //
      //  System.err.println(signatureNode.asText());
      //  String signatureString = signatureNode.asText().substring(5);
      //
      //  SecretKeySpec key =
      //    new SecretKeySpec(getEnvVar("GITHUB_SECRET").getBytes()
      //                      , "HmacSHA1");
      //  Mac hmac = Mac.getInstance("HmacSHA1");
      //  hmac.init(key);
      //  byte[] computedHash = hmac.doFinal(bodyNode.asText().getBytes());
      //  byte[] signature =
      //    Hex.decodeHex(signatureString
      //                  .toCharArray());
      //  if(!java.util.Arrays.equals(signature, computedHash)) {
      //    String msg =
      //      "Expected '"
      //      +Hex.encodeHexString(computedHash)
      //      +"' but received '"
      //      +Hex.encodeHexString(signature);
      //    throw (new IllegalArgumentException(msg));
      //  }
      //}

      incomingJsonNode = mapper.readTree(bodyNode.asText());

      // Get repository key.
      JsonNode repoNode = incomingJsonNode.get("repository");
      if(repoNode == null) {
        String msg = "'repository' key not found in body object.";
        throw (new IllegalArgumentException(msg));
      }

      // Get clone_url key.
      JsonNode cloneURLNode = repoNode.get("clone_url");
      if(cloneURLNode == null) {
        String msg = "'clone_url' key not found in repository object.";
        throw (new IllegalArgumentException(msg));
      }
      String cloneURL = cloneURLNode.asText();

      if(getEnvVar("CLONE_URL") != null
         && !cloneURL.equals(getEnvVar("CLONE_URL"))) {
        String msg =
          "Expected 'clone_url' of "
          + getEnvVar("CLONE_URL")
          + " but received "
          + cloneURL;
        throw (new IllegalArgumentException(msg));
      }


      log(logger, "Git2S3.handleRequest: clone_url is " + cloneURL);

      // This should only really be necessary when running tests.
      deleteDirectory(new File(getEnvVar("TMP_REPO_DIR")));

      log(logger, "Git2S3.handleRequest: begin cloning repo. ");

      // Attempt to clone repository.
      Git.cloneRepository()
        .setURI(cloneURL)
        .setDirectory(new File(getEnvVar("TMP_REPO_DIR")))
        .call();

      // We don't want the .git directory in the destination bucket.
      deleteDirectory(new File(getEnvVar("TMP_REPO_DIR") + "/.git"));
      log(logger, "Git2S3.handleRequest: clone complete. ");

      // Try to load the testing profile.
      AWSCredentialsProvider profileCredProvider =
        new ProfileCredentialsProvider("git2s3test");

      AmazonS3 s3Client = null;
      TransferManager xfer_mgr = null;
      if(profileCredProvider != null) {
        // Credentials could be read out of the profile configuration.
        s3Client = AmazonS3ClientBuilder.standard()
          .withCredentials(profileCredProvider)
          .withRegion(getEnvVar("REGION"))
          .build();
        xfer_mgr = new TransferManager(profileCredProvider);
      } else {
        // Credentials could not be read out of a profile
        // configuration. In this case, we assume we are running on
        // AWS and our credential have already been provided via a
        // role.
        s3Client = AmazonS3ClientBuilder.standard()
          .withRegion(getEnvVar("REGION"))
          .build();
        xfer_mgr = new TransferManager();
      }

      log(logger, "Git2S3.handleRequest: S3 API initiated. ");

      // Prepare list request.
      ListObjectsV2Request req =
        (new ListObjectsV2Request())
             .withBucketName(getEnvVar("BUCKET"))
             .withMaxKeys(2);
      ListObjectsV2Result result;

      do {
        // Execute list request.
        result = s3Client.listObjectsV2(req);

        // Loop through results, deleting each object.
        for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
          log(logger,
              "Git2S3.handleRequest: Delete " + objectSummary.getKey());
          s3Client
            .deleteObject(new DeleteObjectRequest(getEnvVar("BUCKET"),
                                                  objectSummary.getKey()));
        }
        // If there are more than maxKeys keys in the bucket, get a
        // continuation token and list the next objects.
        String token = result.getNextContinuationToken();
        req.setContinuationToken(token);
      } while (result.isTruncated());

      log(logger, "Git2S3.handleRequest: Beginning file transfer. ");

      // Recursively upload all files in the repository the S3 bucket.
      MultipleFileUpload xfer =
        xfer_mgr.uploadDirectory(getEnvVar("BUCKET"),
                                 "",
                                 new File(getEnvVar("TMP_REPO_DIR")),
                                 true);
                                 xfer.waitForCompletion();


      outgoingJsonNode.put("statusCode", 200);
      // XXX: Unnecessary because Github secret validation cannot be
      // done due to how AWS API Gateway proxy integration works.
      //} catch (DecoderException e) {
      //  e.printStackTrace(pw);
      //  log(logger,
      //      "Git2S3.handleRequest: Received DecoderException\n" + e.toString());
      //  outgoingJsonNode.put("statusCode", 500);
      //} catch (NoSuchAlgorithmException e) {
      //  e.printStackTrace(pw);
      //  log(logger,
      //      "Git2S3.handleRequest: Received NoSuchAlgorithmException\n" + e.toString());
      //  outgoingJsonNode.put("statusCode", 500);
      //} catch (InvalidKeyException e) {
      //  e.printStackTrace(pw);
      //  log(logger,
      //      "Git2S3.handleRequest: Received InvalidKeyException\n" + e.toString());
      //  outgoingJsonNode.put("statusCode", 500);
    } catch(InterruptedException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received InterruptedException\n" + e.toString());
          outgoingJsonNode.put("statusCode", 500);
    } catch(AmazonServiceException e) {
      // The call was transmitted successfully, but Amazon S3 couldn't process
      // it, so it returned an error response.
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received AmazonServiceException\n" + e.toString());
      outgoingJsonNode.put("statusCode", 500);
    } catch(SdkClientException e) {
      // Amazon S3 couldn't be contacted for a response, or the client
      // couldn't parse the response from Amazon S3.
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received SdkClientException\n" + e.toString());
      outgoingJsonNode.put("statusCode", 500);
    } catch (JsonProcessingException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received JsonProcessingException\n"
            + e.toString());
      outgoingJsonNode.put("statusCode", 400);
    } catch (IllegalArgumentException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received IllegalArgumentException\n"
            + e.toString());
      outgoingJsonNode.put("statusCode", 400);
    } catch (IOException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received IOException\n" + e.toString());
      outgoingJsonNode.put("statusCode", 500);
    } catch (InvalidRemoteException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received InvalidRemoteException\n"
            + e.toString());
      outgoingJsonNode.put("statusCode", 500);
    } catch (TransportException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received TransportException\n"
            + e.toString());
      outgoingJsonNode.put("statusCode", 500);
    } catch (GitAPIException e) {
      e.printStackTrace(pw);
      log(logger,
          "Git2S3.handleRequest: Received GitAPIException\n"
            + e.toString());
      outgoingJsonNode.put("statusCode", 500);
    }

    writer.write(outgoingJsonNode.toString());
    writer.close();
    return;
  }
}

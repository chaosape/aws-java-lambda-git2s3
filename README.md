# aws-java-lambda-git2s3

This is an experimental AWS Lambda component that will copy all files
from a Github repository into a bucket whenever the input stream
contains a Github JSON push object. To use this function, connect it
to an AWS API Gateway, AWS S3 Bucket, and AWS CloudWatch Logs. Of
course, to connect these other services appropriate permissions will
need to be configured.

Running this Lambda code requires that four environment variables be
specified:

 - REGION: The region of the bucket where the files from the git
 repository will be stored.
 - BUCKET: The name of the bucket where the files from the git
 repository will be stored.
 - TMP_REPO_DIR: The directory where the Lambda code will check out
 the repository locally.
 - CLONE_URL(optional): The clone_url in the Github webhook post that
   is expected.

This lambda code has minimal protections against abuse. If the
'CLONE_URL' environment variable is set then push event must have an
identical 'clone_url' value. This will minimally ensure that arbitrary
repositories cannot be cloned. Some additional precautions can also be
put in place. For example, an AWS WAF instance could be stood up such
that the API Gateway will only ever be exposed traffic from the IPs
Github delivers webhook posts from and a post JSON schema could be
provided to the API gateway. Ideally, we would also like to use the
Github webhook HMAC to verify the incoming request. Unfortunately, I
am not sure how this can be used with the AWS API Gateway Lambda Proxy
Integration functionality. This functionality passes the entire
request to the Lambda as a JSON object. In this object is key named
'body' which contains a serialized version of the request. Because
this serialized version of the request has some formatting differences
from the original request constructed by Github it seems we cannot
compute an identical HMAC signature on reception.

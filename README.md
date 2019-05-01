# aws-java-lambda-git2s3

This is an experimental AWS Lambda component that will copy all files
from a Github git repository into a bucket whenever the input stream
contains a Github JSON push object. To use this function connect it to
an AWS API Gateway, AWS S3 Bucket, and AWS CloudWatch Logs. Of course,
to connect these other services appropriate permissions will need to
be configured.

Running this Lambda code requires that three environment variables be
specified:

 - REGION: The region of the bucket where the files from the git
 repository will be stored.
 - BUCKET: The name of the bucket where the files from the git
 repository will be stored.
 - TMP_REPO_DIR: The directory where the Lambda code will check out
 the repository locally.

Currently, the Lambda code does not do any sort of validation
concerning the source and body of the webhook. A simple way to add
some protect to an application using this code would be to setup an
AWS WAF that restricts incoming requests to the IP range Github claims
Webhook posts will originate from.

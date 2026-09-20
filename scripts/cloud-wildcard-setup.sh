#!/usr/bin/env bash
# ONE-TIME wildcard-TLS plumbing for the cloud host (M8). Idempotent.
#   1. IAM role + instance profile scoped to ONLY the example.com hosted
#      zone (what Caddy's route53 plugin needs for DNS-01) — attached to the
#      running instance, no static keys anywhere.
#   2. IMDSv2 hop limit → 2 so a *container* (Caddy) can reach instance creds.
#   3. Wildcard A record *.BASE_DOMAIN → the Elastic IP.
# After this, deploy the caddy service (build: Dockerfile.caddy) and every
# <venue>.BASE_DOMAIN has TLS with zero per-venue cert steps.
set -euo pipefail

ZONE_ID="${POS_ROUTE53_ZONE:-Z02696742N8MNPIZFBQAV}"
BASE_DOMAIN="${POS_BASE_DOMAIN:-example.com}"
ELASTIC_IP="${POS_CLOUD_IP:-44.206.119.59}"
INSTANCE_ID="${POS_CLOUD_INSTANCE:-i-0f510e6e7db03feef}"
ROLE="pos-caddy-route53"

echo "== IAM role $ROLE (scoped to hosted zone $ZONE_ID) =="
aws iam create-role --role-name "$ROLE" --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"},
                 "Action": "sts:AssumeRole"}]}' 2>/dev/null \
  || echo "-- role exists"
aws iam put-role-policy --role-name "$ROLE" --policy-name route53-dns01 --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {\"Effect\": \"Allow\",
     \"Action\": [\"route53:ListHostedZones\", \"route53:ListHostedZonesByName\", \"route53:GetChange\"],
     \"Resource\": \"*\"},
    {\"Effect\": \"Allow\",
     \"Action\": [\"route53:ChangeResourceRecordSets\", \"route53:ListResourceRecordSets\"],
     \"Resource\": \"arn:aws:route53:::hostedzone/$ZONE_ID\"}]}"
aws iam create-instance-profile --instance-profile-name "$ROLE" 2>/dev/null || echo "-- profile exists"
aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE" 2>/dev/null \
  || echo "-- role already in profile"

echo "== attach instance profile to $INSTANCE_ID =="
if aws ec2 describe-iam-instance-profile-associations \
     --filters "Name=instance-id,Values=$INSTANCE_ID" "Name=state,Values=associated" \
     --query 'IamInstanceProfileAssociations[0].AssociationId' --output text | grep -qv None; then
  echo "-- an instance profile is already associated (verify it is $ROLE)"
else
  # IAM propagation to EC2 takes a few seconds after profile creation. Capture the
  # error so a persistent failure is surfaced, not swallowed.
  attached=""
  for i in 1 2 3 4 5 6; do
    if err=$(aws ec2 associate-iam-instance-profile --instance-id "$INSTANCE_ID" \
               --iam-instance-profile "Name=$ROLE" 2>&1); then
      attached=1; break
    fi
    echo "-- waiting for IAM propagation ($i): $err"; sleep 5
  done
  if [ -z "$attached" ]; then
    echo "ERROR: could not attach instance profile $ROLE to $INSTANCE_ID after 6 attempts" >&2
    echo "       last error: $err" >&2
    exit 1
  fi
fi

echo "== IMDSv2 hop limit 2 (containers must reach instance creds) =="
aws ec2 modify-instance-metadata-options --instance-id "$INSTANCE_ID" \
  --http-tokens required --http-put-response-hop-limit 2 >/dev/null

echo "== wildcard DNS: *.$BASE_DOMAIN → $ELASTIC_IP =="
aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --change-batch "{
  \"Changes\": [{\"Action\": \"UPSERT\", \"ResourceRecordSet\": {
    \"Name\": \"*.$BASE_DOMAIN\", \"Type\": \"A\", \"TTL\": 300,
    \"ResourceRecords\": [{\"Value\": \"$ELASTIC_IP\"}]}}]}" >/dev/null

echo "== done. Deploy the caddy build (BASE_DOMAIN in cloud/infra/.env) and check:"
echo "   docker compose logs caddy | grep -i 'certificate obtained'"

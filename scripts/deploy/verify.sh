#!/usr/bin/env bash
# Read-only post-deploy verification (PLAN_DESPLIEGUE.md §2 pasos 3-6). Safe to run any time; changes nothing.
# Usage: scripts/deploy/verify.sh [backend_url] [frontend_url]
set -u
API="${1:-https://d2pp4cum59aa17.cloudfront.net}"
WEB="${2:-https://frontend-proyecto-handwash.vercel.app}"
pass=0; fail=0
check() { # name, command, expected-substring
  local out; out=$(eval "$2" 2>&1)
  if echo "$out" | grep -q -- "$3"; then echo "OK    $1"; pass=$((pass+1)); else echo "FALLA $1  -> $(echo "$out" | head -c 160)"; fail=$((fail+1)); fi
}
echo "Backend: $API"; echo "Frontend: $WEB"; echo
check "health del backend"                  "curl -fsS --max-time 10 $API/actuator/health"                       '"status":"UP"'
check "estado de despliegue (no clinico)"   "curl -fsS --max-time 10 $API/api/v1/deployment/status"            'NOT_VALIDATED\|no validado\|clinical'
check "protocolos incluyen AGUA_Y_JABON"    "curl -fsS --max-time 10 $API/api/v1/protocols"                    'AGUA_Y_JABON'
check "rutas heredadas deshabilitadas"      "curl -s -o /dev/null -w %{http_code} --max-time 10 -X POST $API/api/deteccion" '40[13]'
check "rutas ADMIN exigen autenticacion"    "curl -s -o /dev/null -w %{http_code} --max-time 10 $API/api/v1/admin/audit-chain" '401'
check "frontend responde"                   "curl -fsS --max-time 10 -I $WEB/"                                  '200'
check "frontend: cabeceras de seguridad"    "curl -fsS --max-time 10 -I $WEB/"                                  'x-content-type-options: nosniff'
check "frontend: CSP en modo informe"       "curl -fsS --max-time 10 -I $WEB/"                                  'content-security-policy-report-only'
if command -v aws >/dev/null 2>&1 && AWS_PROFILE="${AWS_PROFILE:-handwash-deploy}" aws sts get-caller-identity >/dev/null 2>&1; then
  export AWS_PROFILE="${AWS_PROFILE:-handwash-deploy}" AWS_REGION=sa-east-1
  echo; echo "AWS (solo lectura, perfil $AWS_PROFILE):"
  aws cloudwatch describe-alarms --query 'MetricAlarms[].[AlarmName,StateValue]' --output text 2>&1 | head -12
  aws sns list-subscriptions --query 'Subscriptions[].[Protocol,SubscriptionArn]' --output text 2>&1 | sed 's/arn:aws:sns:[^ ]*/<arn>/' | head -3
  aws ecs list-clusters --query 'clusterArns' --output text 2>&1 | head -2
else
  echo; echo "AWS: sin sesion (ejecuta 'aws login --profile handwash-deploy' para ver alarmas y suscripcion SNS)."
fi
echo; echo "Resultado: $pass correctas, $fail con fallo"; [ "$fail" -eq 0 ]

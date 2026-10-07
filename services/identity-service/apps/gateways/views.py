from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .permissions import GatewayRolePermission, gateway_for


class GatewayWhoAmI(APIView):
    """Smoke endpoint mounted under every gateway; proves tenant claims reach the view."""

    permission_classes = [IsAuthenticated, GatewayRolePermission]

    def get(self, request):
        t = request.tenant
        return Response({"gateway": gateway_for(request.path), "role": t.role,
                         "broker_id": t.broker_id, "employer_id": t.employer_id,
                         "tpa_id": t.tpa_id})

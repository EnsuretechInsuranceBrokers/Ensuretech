from rest_framework import generics, status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView
from rest_framework_simplejwt.exceptions import TokenError
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.views import TokenObtainPairView, TokenRefreshView

from .models import BROKER_ROLES, EMPLOYER_ROLES, Role
from .serializers import ChangePasswordSerializer, CreateUserSerializer, UserSerializer


class LoginView(TokenObtainPairView):
    permission_classes = [AllowAny]
    authentication_classes: list = []
    throttle_scope = "auth"


class RefreshView(TokenRefreshView):
    permission_classes = [AllowAny]
    authentication_classes: list = []
    throttle_scope = "auth"


class LogoutView(APIView):
    def post(self, request):
        try:
            RefreshToken(request.data.get("refresh", "")).blacklist()
        except TokenError:
            return Response({"detail": "Invalid refresh token."}, status=status.HTTP_400_BAD_REQUEST)
        return Response(status=status.HTTP_204_NO_CONTENT)


class MeView(generics.RetrieveAPIView):
    serializer_class = UserSerializer

    def get_object(self):
        return self.request.user


class ChangePasswordView(APIView):
    def post(self, request):
        s = ChangePasswordSerializer(data=request.data, context={"request": request})
        s.is_valid(raise_exception=True)
        s.save()
        return Response(status=status.HTTP_204_NO_CONTENT)


class CreateUserView(generics.CreateAPIView):
    """Admins create users strictly inside their own tenant; no self-registration."""

    serializer_class = CreateUserSerializer

    def get_serializer(self, *args, **kwargs):
        data = kwargs.get("data")
        if data is not None:
            kwargs["data"] = self._scope(data.copy())
        return super().get_serializer(*args, **kwargs)

    def _scope(self, data):
        actor = self.request.user
        role = data.get("role")
        if actor.role == Role.SYSTEM:
            return data
        if actor.role == Role.BROKER_ADMIN and role in (BROKER_ROLES | EMPLOYER_ROLES):
            data["broker_id"] = actor.broker_id
            if role in BROKER_ROLES:
                data.pop("employer_id", None)
            return data
        if actor.role == Role.EMPLOYER_ADMIN and role in EMPLOYER_ROLES:
            data["broker_id"] = actor.broker_id
            data["employer_id"] = actor.employer_id
            return data
        from rest_framework.exceptions import PermissionDenied

        raise PermissionDenied("You cannot create users with this role.")

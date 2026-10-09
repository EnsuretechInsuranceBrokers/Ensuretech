from django.contrib.auth import get_user_model
from django.contrib.auth.password_validation import validate_password
from django.utils import timezone
from rest_framework import serializers
from rest_framework_simplejwt.serializers import TokenObtainPairSerializer

User = get_user_model()


class TenantTokenObtainPairSerializer(TokenObtainPairSerializer):
    username_field = "email"

    @classmethod
    def get_token(cls, user):
        token = super().get_token(user)
        token["role"] = user.role
        token["broker_id"] = user.broker_id
        token["employer_id"] = user.employer_id
        token["tpa_id"] = user.tpa_id
        token["portal_legacy_id"] = user.portal_legacy_id
        return token


class UserSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ["id", "email", "first_name", "last_name", "phone", "role",
                  "broker_id", "employer_id", "tpa_id", "is_active"]
        read_only_fields = fields


class CreateUserSerializer(serializers.ModelSerializer):
    password = serializers.CharField(write_only=True)

    class Meta:
        model = User
        fields = ["email", "first_name", "last_name", "phone", "role", "password",
                  "broker_id", "employer_id", "tpa_id"]

    def validate(self, attrs):
        probe = User(**{k: v for k, v in attrs.items() if k != "password"})
        validate_password(attrs["password"], probe)
        return attrs

    def create(self, validated_data):
        from django.core.exceptions import ValidationError as ModelValidationError
        try:
            return User.objects.create_user(**validated_data)
        except ModelValidationError as e:                      # e.g. a TPA login without its tpa_id: the caller's mistake, not a server error
            raise serializers.ValidationError(getattr(e, "message_dict", None) or {"detail": e.messages})


class ChangePasswordSerializer(serializers.Serializer):
    old_password = serializers.CharField(write_only=True)
    new_password = serializers.CharField(write_only=True)

    def validate_old_password(self, value):
        if not self.context["request"].user.check_password(value):
            raise serializers.ValidationError("Incorrect password.")
        return value

    def validate_new_password(self, value):
        validate_password(value, self.context["request"].user)
        return value

    def save(self):
        user = self.context["request"].user
        user.set_password(self.validated_data["new_password"])
        user.password_changed_at = timezone.now()
        user.save(update_fields=["password", "password_changed_at"])
        return user

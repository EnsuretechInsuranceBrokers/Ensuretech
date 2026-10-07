import uuid

from django.contrib.auth.models import AbstractBaseUser, PermissionsMixin
from django.core.exceptions import ValidationError
from django.db import models

from .managers import UserManager


class Role(models.TextChoices):
    """Mirrors public.user_role_enum in the database design."""

    BROKER_ADMIN = "BROKER_ADMIN"
    BROKER_SPECIALIZER = "BROKER_SPECIALIZER"
    BROKER_NON_ADMIN = "BROKER_NON_ADMIN"
    EMPLOYER_ADMIN = "EMPLOYER_ADMIN"
    EMPLOYER_NON_ADMIN = "EMPLOYER_NON_ADMIN"
    TPA_USER = "TPA_USER"
    SYSTEM = "SYSTEM"


BROKER_ROLES = {Role.BROKER_ADMIN, Role.BROKER_SPECIALIZER, Role.BROKER_NON_ADMIN}
EMPLOYER_ROLES = {Role.EMPLOYER_ADMIN, Role.EMPLOYER_NON_ADMIN}


class User(AbstractBaseUser, PermissionsMixin):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    email = models.EmailField(unique=True)
    first_name = models.CharField(max_length=80)
    last_name = models.CharField(max_length=80)
    phone = models.CharField(max_length=20, blank=True)
    role = models.CharField(max_length=20, choices=Role.choices)

    # Tenant scope. Ids reference organization-service rows (no cross-service FKs).
    broker_id = models.IntegerField(null=True, blank=True)
    employer_id = models.IntegerField(null=True, blank=True)
    tpa_id = models.SmallIntegerField(null=True, blank=True)
    portal_legacy_id = models.SmallIntegerField(null=True, blank=True)  # migration window only

    is_active = models.BooleanField(default=True)
    is_staff = models.BooleanField(default=False)
    is_email_verified = models.BooleanField(default=False)
    is_test_data = models.BooleanField(default=False)
    password_changed_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    USERNAME_FIELD = "email"
    REQUIRED_FIELDS = ["first_name", "last_name", "role"]
    objects = UserManager()

    class Meta:
        db_table = "identity_users"
        indexes = [
            models.Index(fields=["role", "broker_id"]),
            models.Index(fields=["employer_id"]),
        ]

    def __str__(self):
        return self.email

    def clean(self):
        super().clean()
        if self.role in BROKER_ROLES and not self.broker_id:
            raise ValidationError({"broker_id": "Required for broker roles."})
        if self.role in EMPLOYER_ROLES and not (self.broker_id and self.employer_id):
            raise ValidationError("Employer roles require broker_id and employer_id.")
        if self.role == Role.TPA_USER and not self.tpa_id:
            raise ValidationError({"tpa_id": "Required for TPA users."})
        if self.role == Role.SYSTEM and (self.broker_id or self.employer_id or self.tpa_id):
            raise ValidationError("SYSTEM users cannot be tenant-scoped.")

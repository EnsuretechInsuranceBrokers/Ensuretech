from django.contrib import admin

from .models import User


@admin.register(User)
class UserAdmin(admin.ModelAdmin):
    list_display = ("email", "role", "broker_id", "employer_id", "tpa_id", "is_active")
    list_filter = ("role", "is_active")
    search_fields = ("email", "first_name", "last_name")
    exclude = ("password",)


from .models import ServiceAccount  # noqa: E402


@admin.register(ServiceAccount)
class ServiceAccountAdmin(admin.ModelAdmin):
    list_display = ("name", "client_id", "is_active", "last_used_at")
    readonly_fields = ("client_id", "last_used_at")   # secrets are managed only via `manage.py service_account`


from .models import BreakGlassGrant  # noqa: E402


@admin.register(BreakGlassGrant)
class BreakGlassGrantAdmin(admin.ModelAdmin):
    list_display = ("attempted_at", "outcome", "client_id", "target_user_id", "reason")
    list_filter = ("outcome",)
    readonly_fields = [f.name for f in BreakGlassGrant._meta.fields]

    def has_add_permission(self, request):
        return False

    def has_delete_permission(self, request, obj=None):
        return False

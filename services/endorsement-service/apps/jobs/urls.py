from django.urls import path

from apps.intake import gui_views as gui
from apps.intake import intake_views as intake_plan
from apps.intake import views as intake
from apps.validation import views as validation

from . import progress, views

urlpatterns = [
    path("", views.JobListView.as_view()),
    path("csv/preview/", intake.CsvPreviewView.as_view()),
    path("csv/upload/", intake.CsvUploadView.as_view()),
    path("csv/mappings/", intake.MappingListCreateView.as_view()),
    path("csv/mappings/<uuid:mapping_id>/", intake.MappingDetailView.as_view()),
    path("intake/plan/", intake_plan.IntakePlanView.as_view()),
    path("intake/template/", intake_plan.IntakeTemplateView.as_view()),
    path("gui/sessions/", gui.GuiSessionCreateView.as_view()),
    path("gui/sessions/<uuid:session_id>/", gui.GuiSessionDetailView.as_view()),
    path("gui/sessions/<uuid:session_id>/grid/validate/", intake_plan.GridValidateView.as_view()),
    path("gui/sessions/<uuid:session_id>/grid/", intake_plan.GridSaveView.as_view()),
    path("gui/sessions/<uuid:session_id>/rows/validate/", gui.GuiValidateView.as_view()),
    path("gui/sessions/<uuid:session_id>/rows/<str:row_key>/", gui.GuiRowView.as_view()),
    path("gui/sessions/<uuid:session_id>/submit/", gui.GuiSubmitView.as_view()),
    path("gui/sessions/<uuid:session_id>/abandon/", gui.GuiAbandonView.as_view()),
    path("<uuid:job_id>/", views.JobDetailView.as_view()),
    path("<uuid:job_id>/audit/", views.JobAuditView.as_view()),
    path("<uuid:job_id>/cancel/", views.JobCancelView.as_view()),
    path("<uuid:job_id>/close/", views.JobCloseView.as_view()),
    path("<uuid:job_id>/progress/", progress.job_progress),
    path("<uuid:job_id>/proceed/", views.JobProceedView.as_view()),
    path("<uuid:job_id>/retry/", views.JobRetryView.as_view()),
    path("<uuid:job_id>/errors/", validation.JobErrorsView.as_view()),
]

"""app releases (launcher / auto update, phase 2.5)

Revision ID: 0002
Revises: 0001
"""
from alembic import op
import sqlalchemy as sa


revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('app_releases',
    sa.Column('release_id', sa.String(), nullable=False),
    sa.Column('app_id', sa.String(), nullable=False),
    sa.Column('package_name', sa.String(), nullable=False),
    sa.Column('version_code', sa.Integer(), nullable=False),
    sa.Column('version_name', sa.String(), nullable=True),
    sa.Column('min_sdk', sa.Integer(), nullable=True),
    sa.Column('sha256', sa.String(), nullable=False),
    sa.Column('size', sa.Integer(), nullable=False),
    sa.Column('cert_sha256', sa.String(), nullable=False),
    sa.Column('file_name', sa.Text(), nullable=False),
    sa.Column('uploaded_at', sa.DateTime(), nullable=False),
    sa.Column('uploaded_by', sa.String(), nullable=True),
    sa.ForeignKeyConstraint(['uploaded_by'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('release_id'),
    sa.UniqueConstraint('app_id', 'version_code', name='uq_app_release_version')
    )
    with op.batch_alter_table('app_releases', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_app_releases_app_id'), ['app_id'], unique=False)


def downgrade() -> None:
    with op.batch_alter_table('app_releases', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_app_releases_app_id'))
    op.drop_table('app_releases')

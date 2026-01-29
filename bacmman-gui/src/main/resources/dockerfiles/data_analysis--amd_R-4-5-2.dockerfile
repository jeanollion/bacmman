FROM quay.io/jupyter/r-notebook:x86_64-lab-4.5.2
USER root
RUN R -e "install.packages(c('data.table', 'ggplot2', 'rjson'))"
USER $NB_UID
# Override settings: dark theme
RUN mkdir -p /opt/conda/share/jupyter/lab/settings
RUN echo '{"@jupyterlab/apputils-extension:themes": {"theme": "JupyterLab Dark"}}' > /opt/conda/share/jupyter/lab/settings/overrides.json
# create working dir
USER root
RUN mkdir -p /data
RUN chmod 777 /data
RUN chown -R $NB_UID:$NB_GID /data
USER $NB_UID
WORKDIR /data
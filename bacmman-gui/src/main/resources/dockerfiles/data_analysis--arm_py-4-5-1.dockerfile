FROM quay.io/jupyter/scipy-notebook:aarch64-lab-4.5.1
#RUN pip install PyBacmman
RUN pip install git+https://git@github.com/jeanollion/PyBacmman.git
#RUN pip install bokeh, selenium # to export bokeh plots as images
RUN pip install plotly kaleido
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